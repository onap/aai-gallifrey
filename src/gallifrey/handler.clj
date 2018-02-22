(ns gallifrey.handler
  (:require [gallifrey.store :as store]
            [utilis.map :refer [map-vals compact]]
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
            [integrant.core :as ig]))

(declare handler)

(defonce ^:private the-store (atom nil))

(defmethod ig/init-key :gallifrey/handler  [_ {:keys [store]}]
  (reset! the-store store)
  handler)

(defmethod ig/halt-key! :gallifrey/handler  [_ _]
  (reset! the-store nil))

(declare serialize de-serialize)

(defn entity-existed?
  [id & {:keys [t-k]}]
  (store/entity-existed? @the-store id :t-k t-k))

(defresource entity-endpoint [id]
  :allowed-methods [:get :put :delete]
  :available-media-types ["application/json"]
  :malformed? (fn [ctx]
                (when (#{:put :delete} (-> ctx :request :request-method))
                  (-> ctx :request :params :actor empty?)))
  :exists? (fn [ctx]
             (if-let [resource (->> (when-let [t-k (-> ctx :request :params :t-k)]
                                      (tf/parse t-k))
                                    (store/get-entity @the-store id :t-k)
                                    serialize)]
               {::resource resource}
               (when (and (= :put (-> ctx :request :request-method))
                          (-> ctx :request :params :create not-empty))
                 true)))
  :existed? (fn [ctx]
              (entity-existed? id :t-k (when-let [t-k (-> ctx :request :params :t-k)]
                                         (tf/parse t-k))))
  :handle-ok ::resource
  :can-put-to-missing? false
  :handle-not-implemented (fn [{{m :request-method} :request :as ctx}]
                            (when (= :put m)
                              (-> (as-response "Resource not found" ctx)
                                  (assoc :status (if (entity-existed? id) 410 404))
                                  (ring-response))))
  :put! (fn [ctx]
          (let [body (json/parse-string (slurp (get-in ctx [:request :body])))
                actor (-> ctx :request :params :actor)
                changes-only (when-let [c (-> ctx :request :params :changes-only)]
                               (boolean c))]
            {::created? (:created? (store/put-entity @the-store actor id body
                                                     :changes-only changes-only))}))
  :delete! (fn [ctx]
             (let [actor (-> ctx :request :params :actor)]
               (store/delete-entity @the-store actor id)))
  :new? ::created?)

(defresource entity-lifespan-endpoint [id]
  :allowed-methods [:get]
  :available-media-types ["application/json"]
  :exists? (fn [ctx]
             (when-let [resource (not-empty
                                  (store/entity-lifespan @the-store id))]
               {::resource (-> resource
                               (update :created str)
                               (update :updated (partial map str))
                               (update :deleted str)
                               compact)}))
  :handle-ok ::resource)

(defroutes app-routes
  (ANY "/entity/:id" [id] (entity-endpoint id))
  (GET "/entity/:id/lifespan" [id] (entity-lifespan-endpoint id))
  (resources "/"))

(def handler
  (-> app-routes
      (wrap-defaults api-defaults)))


;;; Implementation

(defn- serialize
  [e]
  (compact
   (update e :_meta #(map-vals
                      (fn [m]
                        (map-vals str m)) %))))

(defn- de-serialize
  [e]
  e)
