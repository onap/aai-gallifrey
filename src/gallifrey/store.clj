
(ns gallifrey.store
  (:require [utilis.map :refer [compact map-keys map-vals]]
            [utilis.types.keyword :refer [->keyword]]
            [rethinkdb.query :as r]
            [inflections.core :refer [hyphenate underscore]]
            [clj-time.core :as t]
            [metrics.core :refer [default-registry]]
            [metrics.timers :refer [deftimer time!]]
            [integrant.core :as ig]
            [clojure.string :as st])
  (:import [clojure.lang ExceptionInfo]))

(deftimer default-registry ["gallifrey" "store" get-entity-time])
(deftimer default-registry ["gallifrey" "store" put-entity-time])
(deftimer default-registry ["gallifrey" "store" delete-entity-time])
(deftimer default-registry ["gallifrey" "store" aggregate-entities-time])

(deftimer default-registry ["gallifrey" "store" entity-lifespan-time])
(deftimer default-registry ["gallifrey" "store" entity-history-time])

(deftimer default-registry ["gallifrey" "store" entity-existed-time])

(deftimer default-registry ["gallifrey" "store" entity-assertions-time])
(deftimer default-registry ["gallifrey" "store" entity-relationships-time])

(deftimer default-registry ["gallifrey" "store" entity-lifecycle-time])
(deftimer default-registry ["gallifrey" "store" update-lifecycle-time])

(deftimer default-registry ["gallifrey" "store" entity-lock-time])

(declare ensure-db ensure-table ensure-index)

(defmethod ig/init-key :gallifrey/store [_ {:keys [db-server db-name]}]
  (let [{:keys [host port] :or {host "localhost" port 28015}} db-server
        connection (r/connect :host host :port port :db db-name)
        assertions-table "assertions"
        lifecycle-table "lifecycle"
        entity-id-index "entity-id-index"
        entity-type-index "entity-type-index"
        attribute-index "attribute-index"
        value-index "value-index"]
    (ensure-db connection db-name)
    (ensure-table connection db-name assertions-table)
    (ensure-index connection db-name assertions-table entity-id-index :entity-id)
    (ensure-index connection db-name assertions-table entity-type-index :_type)
    (ensure-index connection db-name assertions-table attribute-index :attribute)
    (ensure-index connection db-name assertions-table value-index :value)
    (ensure-table connection db-name lifecycle-table)
    {:db-server db-server
     :db-name db-name
     :assertions-table assertions-table
     :lifecycle-table lifecycle-table
     :entity-id-index entity-id-index
     :entity-type-index entity-type-index
     :attribute-index attribute-index
     :value-index value-index
     :connection connection
     :entity-locks (atom {})}))

(defmethod ig/halt-key! :gallifrey/store [_ {:keys [connection subscriptions]}]
  (.close connection)
  nil)

(declare entity-lock entity-lifecycle update-lifecycle
         entity-assertions entity-relationships)

(defn get-entity
  "Get all the non-retracted attributes associated with an entity referenced by
  entity-id at time 't-t' or 't-k'. 't-k' defaults to 'now' if it is not provided."
  [store entity-type entity-id & {:keys [t-t t-k]}]
  (time!
   get-entity-time
   (let [t-k (or t-k (t/now))]
     (->> (apply entity-assertions store entity-type entity-id (if t-t [:t-t t-t] [:t-k t-k]))
          (reduce (fn [doc {:keys [attribute value] :as a}]
                    (-> doc
                        (assoc (keyword attribute) value)
                        (assoc :_id entity-id :_type entity-type)
                        (assoc-in [:_meta (keyword attribute)]
                                  (dissoc a :id :_type :attribute :value :entity-id))
                        (assoc :_relationships (when (= "entity" entity-type)
                                                 (apply entity-relationships store entity-id
                                                        (if t-t [:t-t t-t] [:t-k t-k]))))))
                  {})
          not-empty))))

(defn entity-existed?
  "Return a boolean indicating whether an entity referenced by entity-id
  ever existed prior to 't-t' or 't-k'. 't-k' defaults to 'now' if it is
  not provided."
  [store entity-type entity-id & {:keys [t-t t-k]}]
  (time!
   entity-existed-time
   (let [t-k (or t-k (t/now))
         deleted (:deleted (entity-lifecycle store entity-type entity-id))]
     (boolean (some #(not (t/after? % (or t-t t-k))) deleted)))))

(defn entity-history
  [store entity-type entity-id]
  "Return a map containing the history of changes to the entity attributes"
  (time!
   entity-history-time
   (when-let [assertions (-> (r/db (:db-name store)) (r/table (:assertions-table store))
                             (r/get-all [entity-id] {:index (:entity-id-index store)})
                             (r/filter (r/fn [row] (r/eq (r/get-field row "_type") entity-type)))
                             (r/run (:connection store)))]
     (->> assertions
          (group-by :attribute)
          (map-vals #(->> (map (fn [a] (compact
                                       (select-keys a [:value
                                                       :k-start :k-end
                                                       :k-start-actor
                                                       :k-end-actor
                                                       :t-start :t-end
                                                       :t-start-actor
                                                       :t-end-actor]))) %)
                          (sort-by :k-start)))))))

(defn entity-lifespan
  "Return a collection of hash maps containing the :created, :updated and
  :deleted timestamps for the entity as a whole."
  [store entity-type entity-id]
  (time!
   entity-lifespan-time
   (let [lifecycle (entity-lifecycle store entity-type entity-id)
         ranges (map (fn [c d] [c d])
                     (:created lifecycle)
                     (concat (:deleted lifecycle) (repeat nil)))
         lifespans (group-by (fn [u]
                               (reduce (fn [u [c d]]
                                         (if (and (t/before? c u)
                                                  (or (nil? d)
                                                      (t/before? u d)))
                                           (reduced [c d])
                                           u)) u ranges))
                             (:updated lifecycle))]
     (mapv (fn [[c d]]
             (compact {:created c :deleted d
                       :updated (get lifespans [c d])})) ranges))))

(defn put-entity
  "Stores the assertions included in 'doc' and automatically retracts any prior
  assertions against the attributes in 'doc'. If 'changes-only' is set to true,
  only attributes included in 'doc' that are asserting new values are captured.

  The 'actor' making the assertions must be provided as a string."
  [store actor entity-type entity-id doc & {:keys [t-t changes-only]
                                            :or {changes-only true}}]
  (time!
   put-entity-time
   (locking (entity-lock store entity-type entity-id)
     (let [entity (entity-lifecycle store entity-type entity-id)
           now (t/now)
           doc (map-keys name doc)
           attributes (->> doc keys set)
           assertions (->> (dissoc doc "id")
                           (map (fn [[k v]]
                                  (compact
                                   {:entity-id (str entity-id)
                                    :_type entity-type
                                    :attribute k
                                    :value v
                                    :k-start now
                                    :k-start-actor actor
                                    :t-start t-t})))
                           (remove nil?))
           write-assertions (fn [assertions]
                              (-> (r/db (:db-name store)) (r/table (:assertions-table store))
                                  (r/insert assertions {:conflict :replace})
                                  (r/run (:connection store))))]
       (if (or (not entity)
               (and (not-empty (:deleted entity))
                    (t/before? (last (:created entity)) (last (:deleted entity)))
                    (t/before? (last (:deleted entity)) now)))
         (do (write-assertions assertions)
             (update-lifecycle store entity-type (-> entity (assoc :id entity-id)
                                                     (update :created conj now)))
             {:created? true})
         (let [existing-assertions (entity-assertions store entity-type entity-id :t-k now)
               duplicates (set
                           (when changes-only
                             (->> existing-assertions
                                  (filter (fn [{:keys [attribute value]}]
                                            (= value (get doc attribute))))
                                  (map :attribute))))
               attributes (->> attributes (remove duplicates) set)
               retractions (->> existing-assertions
                                (filter #(attributes (:attribute %)))
                                (map #(-> (assoc % :k-end now :k-end-actor actor
                                                 :t-end t-t)
                                          (assoc :t-end-actor (when t-t actor))
                                          compact)))
               assertions (remove #(duplicates (:attribute %)) assertions)]
           (when (not-empty assertions)
             (write-assertions (concat retractions assertions))
             (update-lifecycle store entity-type (update entity :updated conj now)))
           (merge
            {:updated (mapv :attribute assertions)}
            (when (not-empty duplicates)
              {:ignored (vec duplicates)}))))))))

(defn delete-entity
  "Automatically retracts all the assertions made against the entity referenced
  by 'entity-id'"
  [store actor entity-type entity-id]
  (time!
   delete-entity-time
   (let [now (t/now)
         retractions (->> (entity-assertions store entity-type entity-id :t-k now)
                          (map #(assoc % :k-end now :k-end-actor actor)))]
     (update-lifecycle store entity-type
                       (update (entity-lifecycle store entity-type entity-id) :deleted conj now))
     (-> (r/db (:db-name store)) (r/table (:assertions-table store))
         (r/insert retractions {:conflict :replace})
         (r/run (:connection store))))))

(defn aggregate-entities
  [store entity-type & {:keys [filters properties t-t t-k]}]
  (time!
   aggregate-entities-time
   (let [t-k (or t-k (t/now))
         properties (map keyword (st/split properties #","))
         entities (-> (r/db (:db-name store)) (r/table (:assertions-table store))
                      (r/get-all [entity-type] {:index (:entity-type-index store)})
                      (cond-> t-t (r/filter (r/fn [row]
                                              (r/and
                                               (r/le (r/get-field row "t-start") t-t)
                                               (r/or (r/not (r/has-fields row "t-end"))
                                                     (r/gt (r/get-field row "t-end") t-t)))))
                              t-k (r/filter (r/fn [row]
                                              (r/and
                                               (r/le (r/get-field row "k-start") t-k)
                                               (r/or (r/not (r/has-fields row "k-end"))
                                                     (r/gt (r/get-field row "k-end") t-k))))))
                      (r/run (:connection store))
                      (->> (reduce (fn [entities {:keys [entity-id attribute value]}]
                                     (assoc-in entities
                                               [entity-id (keyword attribute)]
                                               value)) {})
                           (map (fn [[id e]]
                                  (assoc e :_id id :_type entity-type))))
                      (cond->> (not-empty filters)
                        (filter #(every? (fn [k] (= (get % k) (get filters k))) (keys filters)))))]
     (cond-> {:total (count entities)}
       (not-empty properties)
       (merge {:aggregations (->> properties
                                  (map (fn [p]
                                         [p (->> entities (group-by #(get % p))
                                                 (map (fn [[value entities]]
                                                        {:key value
                                                         :doc_count (count entities)})))]))
                                  (into {}))})))))

;;; Implementation

(defn- entity-lock
  [store entity-type entity-id]
  (time!
   entity-lock-time
   (locking (:entity-locks store)
     (if-let [l (get @(:entity-locks store) [entity-type entity-id])]
       l
       (let [l (Object.)]
         (swap! (:entity-locks store) assoc [entity-type entity-id] l)
         l)))))

(defn- entity-lifecycle
  [store entity-type entity-id]
  (time!
   entity-lifecycle-time
   (-> (r/db (:db-name store)) (r/table (:lifecycle-table store))
       (r/get entity-id)
       (r/run (:connection store)))))

(defn- update-lifecycle
  [store entity-type lifecycle-record]
  (time!
   update-lifecycle-time
   (-> (r/db (:db-name store)) (r/table (:lifecycle-table store))
       (r/insert (assoc lifecycle-record :entity-type entity-type) {:conflict :update})
       (r/run (:connection store)))))

(defn fetch-assertions
  [store entity-type id index & {:keys [t-t t-k]}]
  (-> (r/db (:db-name store)) (r/table (:assertions-table store))
      (r/get-all [id] {:index index})
      (r/filter (r/fn [row] (r/eq (r/get-field row "_type") entity-type)))
      (cond-> t-t (r/filter (r/fn [row]
                              (r/and
                               (r/le (r/get-field row "t-start") t-t)
                               (r/or (r/not (r/has-fields row "t-end"))
                                     (r/gt (r/get-field row "t-end") t-t)))))
              t-k (r/filter (r/fn [row]
                              (r/and
                               (r/le (r/get-field row "k-start") t-k)
                               (r/or (r/not (r/has-fields row "k-end"))
                                     (r/gt (r/get-field row "k-end") t-k))))))
      (r/run (:connection store))))

(defn entity-assertions
  [store entity-type entity-id & {:keys [t-t t-k]}]
  (time!
   entity-assertions-time
   (fetch-assertions store entity-type entity-id (:entity-id-index store) :t-t t-t :t-k t-k)))

(defn entity-relationships
  [store entity-id & {:keys [t-t t-k]}]
  (time!
   entity-relationships-time
   (->> (fetch-assertions store "relationship" entity-id (:value-index store)
                          :t-t t-t :t-k t-k)
        (map :entity-id) distinct
        (pmap #(get-entity store "relationship" % :t-t t-t :t-k t-k))
        compact)))

(defn- ensure-db
  [conn db-name]
  (when-not ((set (r/run (r/db-list) conn)) db-name)
    (r/run (r/db-create db-name) conn)))

(defn- ensure-table
  [conn db-name assertions-table & {:keys [init-fn]}]
  (when-not ((set (r/run (r/table-list (r/db db-name)) conn)) assertions-table)
    (-> (r/db db-name) (r/table-create assertions-table) (r/run conn))))

(defn- ensure-index
  [conn db-name assertions-table index-name field]
  (when-not ((set (-> (r/db db-name) (r/table assertions-table)
                      (r/index-list) (r/run conn))) index-name)
    (-> (r/db db-name)
        (r/table assertions-table)
        (r/index-create index-name (r/fn [row]
                                     (r/get-field row (->keyword field))))
        (r/run conn))))
