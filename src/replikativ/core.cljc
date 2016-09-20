(ns replikativ.core
  "Replication related pub-sub protocols."
  (:require [replikativ.crdt.materialize :refer [key->crdt get-crdt]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.protocols :refer [-downstream -handshake]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            [clojure.data :refer [diff]]
            #?(:clj [full.async :refer [<? <<? <?? go-try go-loop-try alt?]])
            #?(:clj [full.lab :refer [go-for go-loop-super go-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close! go]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.async :refer [<<? <? go-try go-loop-try go-loop-super alt?]]
                            [full.lab :refer [go-for go-super go-loop-super]])))


(defn- initial-handshake [cold-store mem-store identities out sub-id]
  (go-for [[user crdts] identities
           id crdts
           :let [{:keys [crdt state]} (<? (get-crdt cold-store mem-store [user id]))]
           :when state
           :let [state (-handshake state)]]
          (do
            (debug {:event :sending-handshake :crdt [user id] :id sub-id})
            (>! out {:user user
                     :crdt-id id
                     :type :pub/downstream
                     :id sub-id
                     :downstream {:crdt crdt
                                  :method :handshake
                                  :op state}}))))


(defn- publish-out
  "Reply to publications by sending an update value filtered to subscription."
  [cold-store mem-store pub-ch out identities remote-pn sub-id]
  (go-super
   (debug {:event :initial-handshake :subscriptions identities})
   (<<? (initial-handshake cold-store mem-store identities out sub-id))

   (debug {:event :starting-publication})
   (go-loop-super [{:keys [downstream id user crdt-id] :as p} (<? pub-ch)]
                  (if-not p
                    (info {:event :publication-ending :remote-peer remote-pn
                           :subscriptions identities})
                    (do
                      (when (get-in identities [user crdt-id])
                        (info {:event :sending-publication :id (:id p) :to-remote-peer remote-pn})
                        (>! out p))
                      (recur (<? pub-ch)))))))


(defn subscribe-out [remote-pn remote-subs sub-out-ch out extend?]
  (go-loop-super [{:keys [identities] :as s} (<? sub-out-ch)
                  old-subs nil]
                 (when s
                   (let [new-subs (if extend? identities
                                      (let [[_ _ common-subs] (diff identities remote-subs)]
                                        (or common-subs {})))]
                     (when-not (= new-subs old-subs)
                       (debug {:event :subscribing-to :remote-peer remote-pn :subscriptions new-subs})
                       (>! out (assoc s :identities new-subs)))
                     (recur (<? sub-out-ch) new-subs)))))

(defn subscribe-in
  "Adjust publication stream and propagate subscription requests."
  [peer sub-ch out]
  (let [{:keys [chans log mem-store cold-store]} (-> @peer :volatile)
        [bus-in bus-out] chans
        pn (:id @peer)
        sub-out-ch (chan)]
    (go-loop-super [{:keys [identities id extend?] :as s} (<? sub-ch)
                    old-identities nil
                    old-pub-ch nil
                    old-sub-ch nil]
                   (if s
                     (if (= old-identities identities)
                       (do (info {:event :redundant-subscription :subscriptions identities})
                           (>! out {:type :sub/identities-ack :id id})
                           (recur (<? sub-ch) old-identities old-pub-ch old-sub-ch))
                       (let [[old-subs new-subs]
                             (<? (k/update-in cold-store
                                              [:peer-config :sub :subscriptions]
                                              ;; TODO filter here
                                              (fn [old] (merge-with set/union old identities))))

                             remote-pn (:sender s)
                             pub-ch (chan 10000) ;; buffer for initial handshake backlog
                             sub-out-ch (chan)
                             extend-me? (true? (<? (k/get-in cold-store [:peer-config :sub :extend?])))]
                         (info {:event :starting-subscription :peer pn :id id :remote-peer remote-pn})
                         (debug {:event :subscribing :peer pn :subscriptions identities})

                         (when old-pub-ch
                           (unsub bus-out :pub/downstream old-pub-ch)
                           (close! old-pub-ch))
                         (sub bus-out :pub/downstream pub-ch)
                         (<? (publish-out cold-store mem-store pub-ch out identities remote-pn id))

                         (when old-sub-ch
                           (unsub bus-out :sub/identities old-sub-ch)
                           (close! old-sub-ch))
                         (sub bus-out :sub/identities sub-out-ch)
                         (subscribe-out remote-pn identities sub-out-ch out extend?)

                         (let [msg {:type :sub/identities
                                    :identities new-subs
                                    :id id
                                    :extend? extend-me?}]
                           (when (= new-subs old-subs)
                             (debug {:event :ensuring-backsubscription :id id})
                             (>! sub-out-ch msg))
                           (when (not (= new-subs old-subs))
                             (debug {:event :notifying-all-peers-of-subscription})
                             (alt? [[bus-in msg]]
                                   :wrote

                                   (timeout (* 60 1000))
                                   ;; TODO disconnect peer
                                   (throw (ex-info "bus-in was blocked for a long time. Peer broken."
                                                   {:type :bus-in-block
                                                    :failed-put msg
                                                    :was-blocked-by (<? bus-in)})))))

                         (>! out {:type :sub/identities-ack :id id})
                         (info {:event :finishing-subscription :peer pn :id id})

                         (recur (<? sub-ch) identities pub-ch sub-out-ch)))
                     (do (info {:event :subscribe-closing-old-pub-ch :subs identities})
                         (unsub bus-out :pub/downstream old-pub-ch)
                         (unsub bus-out :sub/identities out)
                         (when old-pub-ch (close! old-pub-ch)))))))


(defn commit-pub [cold-store mem-store [user crdt-id] pub]
  (go-try
   ;; ensure that we have a copy in memory! and don't append something before
   ;; we can update the in memory datastructure
   (<? (get-crdt cold-store mem-store [user crdt-id]))
   (let [[first-id id] (<? (k/append cold-store [user crdt-id :log] pub))
         [old-state new-state]
         (<? (k/update-in mem-store [[user crdt-id]]
                          (fn [{:keys [description public state crdt]}]
                            (let [state (or state (key->crdt (:crdt pub)))]
                              {:crdt (or crdt (:crdt pub))
                               :description (or description
                                                (:description pub))
                               :public (or (:public pub) public false)
                               :state (-downstream state (:op pub))}))))]
     ;; TODO prune log with state from time to time
     (when (and (< (rand) 0.001) (not= first-id id))
       (debug {:event :log-pruning :crdt [user crdt-id]})
       (<? (k/assoc-in cold-store [first-id] {:next id
                                              :elem {:crdt (:crdt new-state)
                                                     :method :handshake
                                                     :op (-handshake (:state new-state))}})))
     [old-state new-state])))


(defn publish-in
  "Synchronize downstream publications."
  [peer pub-ch bus-in out]
  (go-loop-super [{:keys [downstream id crdt-id user] :as p} (<? pub-ch)]
                 (when p
                   (let [{pn :id {:keys [mem-store cold-store]} :volatile} @peer]
                     (info {:event :publish-in :id (:id p) :peer pn})
                     (let [[old-state new-state] (<? (commit-pub cold-store mem-store
                                                                 [user crdt-id] downstream))]
                       (>! out {:type :pub/downstream-ack
                                :user user
                                :crdt-id crdt-id
                                :id id})
                       (when (not= old-state new-state)
                         (info {:event :publish-downstream :peer pn :id (:id p)})
                         (alt? [[bus-in p]]
                               (debug {:event :sent-new-downstream-pubs :peer pn})

                               (timeout (* 60 1000)) ;; TODO make tunable
                               (throw (ex-info "bus-in was blocked for a long time. Peer broken."
                                               {:type :bus-in-block
                                                :failed-put p
                                                :was-blocked-by (<? bus-in)}))))))
                   (recur (<? pub-ch)))))



(defn wire
  "Wire a peer to an output (response) channel and a publication by :type of the input."
  [[peer [in out]]]
  (let [new-in (chan)]
    (go-try (let [p (pub in (fn [{:keys [type]}]
                              (or ({:sub/identities :sub/identities
                                    :pub/downstream :pub/downstream} type)
                                  :unrelated)))
                  {{:keys [store chans log]} :volatile
                   name :name} @peer
                  [bus-in bus-out] chans
                  pub-in-ch (chan)
                  sub-in-ch (chan)]

              (sub p :sub/identities sub-in-ch)
              (subscribe-in peer sub-in-ch out)

              (sub p :pub/downstream pub-in-ch)
              (publish-in peer pub-in-ch bus-in out)

              (sub p :unrelated new-in)))
    [peer [new-in out]]))
