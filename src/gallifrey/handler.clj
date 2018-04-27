(ns gallifrey.handler
  (:require [gallifrey.store :as store]
            [utilis.map :refer [map-vals compact]]
            [utilis.fn :refer [fsafe apply-kw]]
            [liberator.core :refer [defresource]]
            [liberator.representation :refer [as-response ring-response]]
            [compojure.core :refer [GET PUT PATCH ANY defroutes]]
            [compojure.route :refer [resources]]
            [ring.util.response :refer [resource-response content-type]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.session :refer [wrap-session]]
            [cheshire.core :as json]
            [clj-time.format :as tf]
            [metrics.ring.instrument :refer [instrument]]
            [metrics.ring.expose :refer [expose-metrics-as-json]]
            [integrant.core :as ig]))

(declare handler)

(defonce ^:private the-store (atom nil))

(defmethod ig/init-key :gallifrey/handler  [_ {:keys [store]}]
  (reset! the-store store)
  handler)

(defmethod ig/halt-key! :gallifrey/handler  [_ _]
  (reset! the-store nil))

(declare parse-ts serialize serialize-entity serialize-relationship)

(defn entity-existed?
  [type id & {:keys [t-k]}]
  (store/entity-existed? @the-store type id :t-k t-k))

(defresource entity-endpoint [type id]
  :allowed-methods [:get :put :delete]
  :available-media-types ["application/json"]
  :malformed? (fn [ctx]
                (when (#{:put :delete} (-> ctx :request :request-method))
                  (-> ctx :request :params :actor empty?)))
  :exists? (fn [ctx]
             (let [put? (= :put (-> ctx :request :request-method))]
               (if-let [resource (if put?
                                   (store/get-entity @the-store type id :t-k (parse-ts ctx :t-k))
                                   (store/get-entity @the-store type id
                                                     :t-t (parse-ts ctx :t-t) :t-k (parse-ts ctx :t-k)))]
                 {::resource ((case type
                                "entity" serialize-entity
                                "relationship" serialize-relationship) resource)}
                 (when (and put? (-> ctx :request :params :create not-empty)) true))))
  :existed? (fn [ctx] (entity-existed? type id :t-k (parse-ts ctx :t-k)))
  :handle-ok ::resource
  :can-put-to-missing? false
  :handle-not-implemented (fn [{{m :request-method} :request :as ctx}]
                            (when (= :put m)
                              (-> (as-response "Resource not found" ctx)
                                  (assoc :status (if (entity-existed? type id) 410 404))
                                  ring-response)))
  :put! (fn [ctx]
          (let [body (json/parse-string (slurp (get-in ctx [:request :body])))
                properties (get body "properties")
                source {"source.type" (get-in body ["source" "type"])
                        "source.id" (get-in body ["source" "id"])}
                target {"target.type" (get-in body ["target" "type"])
                        "target.id" (get-in body ["target" "id"])}
                actor (-> ctx :request :params :actor)
                changes-only (boolean (-> ctx :request :params :changes-only))]
            {::created? (:created? (store/put-entity @the-store actor type id
                                                     (compact (merge properties source target))
                                                     :t-t (parse-ts ctx :t-t)
                                                     :changes-only changes-only))}))
  :delete! (fn [ctx]
             (store/delete-entity @the-store (-> ctx :request :params :actor) type id))
  :new? ::created?)

(defresource entity-history-endpoint [type id]
  :allowed-methods [:get]
  :available-media-types ["application/json"]
  :exists? (fn [ctx]
             (when-let [resource (not-empty
                                  (store/entity-history @the-store type id))]
               {::resource (map-vals (partial map #(-> %
                                                 (update :k-start str)
                                                 (update :k-end str)
                                                 (update :t-start str)
                                                 (update :t-end str)
                                                 compact)) resource)}))
  :handle-ok ::resource)

(defresource entity-lifespan-endpoint [type id]
  :allowed-methods [:get]
  :available-media-types ["application/json"]
  :exists? (fn [ctx]
             (when-let [resource (not-empty
                                  (store/entity-lifespan @the-store type id))]
               {::resource (map #(-> %
                                     (update :created str)
                                     (update :updated (partial map str))
                                     (update :deleted str)
                                     compact) resource)}))
  :handle-ok ::resource)

(defresource entity-aggregation-endpoint [type]
  :allowed-methods [:get]
  :available-media-types ["application/json"]
  :handle-ok #(store/aggregate-entities @the-store type
                                        :filters (->> (dissoc (get-in % [:request :params])
                                                              :properties :t-t :t-k
                                                              :gallifrey-type)
                                                      (map (fn [[k v]]
                                                             (cond
                                                               (= "null" v) [k nil]
                                                               :else [k v])))
                                                      (into {}))
                                        :properties (get-in % [:request :params :properties])
                                        :t-t (parse-ts % :t-t) :t-k (parse-ts % :t-k)))

(defroutes app-routes
  (GET "/:gallifrey-type/aggregations" [gallifrey-type] (entity-aggregation-endpoint gallifrey-type))
  (ANY "/:gallifrey-type/:id" [gallifrey-type id] (entity-endpoint gallifrey-type id))
  (GET "/:gallifrey-type/:id/history" [gallifrey-type id] (entity-history-endpoint gallifrey-type id))
  (GET "/:gallifrey-type/:id/lifespan" [gallifrey-type id] (entity-lifespan-endpoint gallifrey-type id))
  (resources "/"))

(def handler
  (-> app-routes
      (wrap-defaults api-defaults)
      instrument
      expose-metrics-as-json))


;;; Implementation

(defn- parse-ts
  [ctx key]
  (when-let [ts (get-in ctx [:request :params key])]
    (tf/parse ts)))

(defn- serialize-relationship
  [resource]
  (compact
   {:properties (dissoc resource :_id :_meta
                        :source.type :source.id
                        :target.type :target.id)
    :source {:type (:source.type resource)
             :id (:source.id resource)}
    :target {:type (:target.type resource)
             :id (:target.id resource)}
    :_id (:_id resource)
    :_meta (map-vals (partial map-vals str) (:_meta resource))}))

(defn- serialize-entity
  [resource]
  (compact
   {:properties (dissoc resource :_id :_meta :_relationships)
    :relationships (map serialize-relationship (:_relationships resource))
    :_id (:_id resource)
    :_meta (map-vals (partial map-vals str) (:_meta resource))}))
