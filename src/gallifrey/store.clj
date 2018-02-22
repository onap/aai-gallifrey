(ns gallifrey.store
  (:require [utilis.map :refer [compact map-keys map-vals]]
            [utilis.types.keyword :refer [->keyword]]
            [rethinkdb.query :as r]
            [inflections.core :refer [hyphenate underscore]]
            [clj-time.core :as t]
            [integrant.core :as ig])
  (:import [clojure.lang ExceptionInfo]))

(declare ensure-db ensure-table ensure-index)

(defmethod ig/init-key :gallifrey/store [_ {:keys [db-server db-name]}]
  (let [{:keys [host port] :or {host "localhost" port 28015}} db-server
        connection (r/connect :host host :port port :db db-name)
        assertions-table "assertions"
        lifecycle-table "lifecycle"
        entity-id-index "entity-id-index"]
    (ensure-db connection db-name)
    (ensure-table connection db-name assertions-table)
    (ensure-index connection db-name assertions-table entity-id-index :entity-id)
    (ensure-table connection db-name lifecycle-table)
    {:db-server db-server
     :db-name db-name
     :assertions-table assertions-table
     :lifecycle-table lifecycle-table
     :entity-id-index entity-id-index
     :connection connection
     :entity-locks (atom {})}))

(defmethod ig/halt-key! :gallifrey/store [_ {:keys [connection subscriptions]}]
  (.close connection)
  nil)


(declare entity-lock entity-lifecycle update-lifecycle entity-assertions)

(defn get-entity
  "Get all the non-retracted attributes associated with an entity referenced by
  entity-id at time 't-k'. 't-k' defaults to 'now' if it is not provided."
  [store entity-id & {:keys [t-k]}]
  (let [t-k (or t-k (t/now))]
    (->> (entity-assertions store entity-id :t-k t-k)
         (reduce (fn [doc {:keys [attribute value] :as a}]
                   (-> doc
                       (assoc (keyword attribute) value)
                       (assoc-in [:_meta (keyword attribute)]
                                 (dissoc a :id :attribute :value :entity-id))))
                 {})
         not-empty)))

(defn entity-existed?
  "Return a boolean indicating whether an entity referenced by entity-id
  ever existed prior to 't-k'. 't-k' defaults to 'now' if it is not provided."
  [store entity-id & {:keys [t-k]}]
  (let [t-k (or t-k (t/now))
        deleted (:deleted (entity-lifecycle store entity-id))]
    (boolean (some #(t/before? % t-k) deleted))))

(defn entity-lifespan
  "Return a collection of hash map containing the :created, :updated and
  :deleted timestamps for the entity as a whole."
  [store entity-id]
  (let [lifecycle (entity-lifecycle store entity-id)
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
                      :updated (get lifespans [c d])})) ranges)))

(defn put-entity
  "Stores the assertions included in 'doc' and automatically retracts any prior
  assertions against the attributes in 'doc'. If 'changes-only' is set to true,
  only attributes included in 'doc' that are asserting new values are captured.

  The 'actor' making the assertions must be provided as a string."
  [store actor entity-id doc & {:keys [changes-only]
                                :or {changes-only true}}]
  (locking (entity-lock store entity-id)
    (let [entity (entity-lifecycle store entity-id)
          now (t/now)
          doc (map-keys name doc)
          attributes (->> doc keys set)
          assertions (->> (dissoc doc "id")
                          (map (fn [[k v]]
                                 (compact
                                  {:entity-id (str entity-id)
                                   :attribute k
                                   :value v
                                   :k-start now
                                   :k-start-actor actor})))
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
            (update-lifecycle store (-> entity (assoc :id entity-id)
                                        (update :created conj now)))
            {:created? true})
        (let [existing-assertions (entity-assertions store entity-id :t-k now)
              duplicates (set
                          (when changes-only
                            (->> existing-assertions
                                 (filter (fn [{:keys [attribute value]}]
                                           (= value (get doc attribute))))
                                 (map :attribute))))
              attributes (->> attributes (remove duplicates) set)
              retractions (->> existing-assertions
                               (filter #(attributes (:attribute %)))
                               (map #(assoc % :k-end now :k-end-actor actor)))
              assertions (remove #(duplicates (:attribute %)) assertions)]
          (when (not-empty assertions)
            (write-assertions (concat retractions assertions))
            (update-lifecycle store (update entity :updated conj now)))
          (merge
           {:updated (mapv :attribute assertions)}
           (when (not-empty duplicates)
             {:ignored (vec duplicates)})))))))

(defn delete-entity
  "Automatically retracts all the assertions made against the entity referenced
  by 'entity-id'"
  [store actor entity-id]
  (let [now (t/now)
        retractions (->> (entity-assertions store entity-id :t-k now)
                         (map #(assoc % :k-end now :k-end-actor actor)))]
    (update-lifecycle store (update (entity-lifecycle store entity-id) :deleted conj now))
    (-> (r/db (:db-name store)) (r/table (:assertions-table store))
        (r/insert retractions {:conflict :replace})
        (r/run (:connection store)))))

;;; Implementation

(defn- entity-lock
  [store entity-id]
  (locking (:entity-locks store)
    (if-let [l (get @(:entity-locks store) entity-id)]
      l
      (let [l (Object.)]
        (swap! (:entity-locks store) assoc entity-id l)
        l))))

(defn- entity-lifecycle
  [store entity-id]
  (-> (r/db (:db-name store)) (r/table (:lifecycle-table store))
      (r/get entity-id)
      (r/run (:connection store))))

(defn- update-lifecycle
  [store lifecycle-record]
  (-> (r/db (:db-name store)) (r/table (:lifecycle-table store))
      (r/insert lifecycle-record {:conflict :update})
      (r/run (:connection store))))

(defn entity-assertions
  [store entity-id & {:keys [t-k]}]
  (-> (r/db (:db-name store)) (r/table (:assertions-table store))
      (r/get-all [entity-id] {:index (:entity-id-index store)})
      (cond-> t-k (r/filter (r/fn [row]
                              (r/and
                               (r/le (r/get-field row "k-start") t-k)
                               (r/or (r/not (r/has-fields row "k-end"))
                                     (r/gt (r/get-field row "k-end") t-k))))))
      (r/run (:connection store))))

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
