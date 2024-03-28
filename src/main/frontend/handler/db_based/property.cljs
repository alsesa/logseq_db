(ns frontend.handler.db-based.property
  "Properties handler for db graphs"
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db.model :as model]
            [frontend.format.block :as block]
            [frontend.handler.notification :as notification]
            [frontend.handler.db-based.property.util :as db-pu]
            [logseq.outliner.core :as outliner-core]
            [frontend.util :as util]
            [frontend.state :as state]
            [logseq.common.util :as common-util]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.db.frontend.property.util :as db-property-util]
            [malli.util :as mu]
            [malli.error :as me]
            [logseq.common.util.page-ref :as page-ref]
            [datascript.impl.entity :as e]
            [logseq.db.frontend.property :as db-property]
            [frontend.handler.property.util :as pu]
            [promesa.core :as p]
            [frontend.db.async :as db-async]
            [logseq.db :as ldb]))

;; schema -> type, cardinality, object's class
;;           min, max -> string length, number range, cardinality size limit

(defn built-in-validation-schemas
  "A frontend version of built-in-validation-schemas that adds the current database to
   schema fns"
  [property & {:keys [new-closed-value?]
               :or {new-closed-value? false}}]
  (into {}
        (map (fn [[property-type property-val-schema]]
               (cond
                 (db-property-type/closed-value-property-types property-type)
                 (let [[_ schema-opts schema-fn] property-val-schema
                       schema-fn' (if (db-property-type/property-types-with-db property-type) #(schema-fn (db/get-db) %) schema-fn)]
                   [property-type [:fn
                                   schema-opts
                                   #((db-property-type/type-or-closed-value? schema-fn') (db/get-db) property % new-closed-value?)]])
                 (db-property-type/property-types-with-db property-type)
                 (let [[_ schema-opts schema-fn] property-val-schema]
                   [property-type [:fn schema-opts #(schema-fn (db/get-db) %)]])
                 :else
                 [property-type property-val-schema]))
             db-property-type/built-in-validation-schemas)))

(defn- fail-parse-long
  [v-str]
  (let [result (parse-long v-str)]
    (or result
        (throw (js/Error. (str "Can't convert \"" v-str "\" to a number"))))))

(defn- fail-parse-double
  [v-str]
  (let [result (parse-double v-str)]
    (or result
        (throw (js/Error. (str "Can't convert \"" v-str "\" to a number"))))))

(defn- infer-schema-from-input-string
  [v-str]
  (try
    (cond
      (fail-parse-long v-str) :number
      (fail-parse-double v-str) :number
      (util/uuid-string? v-str) :page
      (common-util/url? v-str) :url
      (contains? #{"true" "false"} (string/lower-case v-str)) :checkbox
      :else :default)
    (catch :default _e
      :default)))

(defn convert-property-input-string
  [schema-type v-str]
  (if (and (not (string? v-str)) (not (object? v-str)))
    v-str
    (case schema-type
      :number
      (fail-parse-double v-str)

      :page
      (uuid v-str)

      ;; these types don't need to be translated. :date expects uuid and other
      ;; types usually expect text
      (:url :date :any)
      v-str

      ;; :default
      (if (util/uuid-string? v-str) (uuid v-str) v-str))))

(defn upsert-property!
  [repo property-id schema {:keys [property-name]}]
  (let [db-ident (or property-id (db-property/get-db-ident-from-name property-name))
        property (db/entity db-ident)
        k-name (or (:block/original-name property) (name property-name))]
    (if property
      (db/transact! repo [(cond->
                           (outliner-core/block-with-updated-at
                            {:db/ident db-ident
                             :block/schema schema})
                            (= :many (:cardinality schema))
                            (assoc :db/cardinality :db.cardinality/many))]
                    {:outliner-op :save-block})
      (db/transact! repo [(sqlite-util/build-new-property k-name schema {:db-ident db-ident})]
                    {:outliner-op :new-property}))
    db-ident))

(defn validate-property-value
  [schema value]
  (me/humanize (mu/explain-data schema value)))

(defn- reset-block-property-multiple-values!
  [repo block-id property-id values _opts]
  (let [block (db/entity repo [:block/uuid block-id])
        property (db/entity property-id)
        property-name (:block/original-name property)
        values (remove nil? values)
        property-schema (:block/schema property)
        {:keys [type cardinality]} property-schema
        multiple-values? (= cardinality :many)]
    (when (and multiple-values? (seq values))
      (let [infer-schema (when-not type (infer-schema-from-input-string (first values)))
            property-type (or type infer-schema :default)
            schema (get (built-in-validation-schemas property) property-type)
            values' (try
                      (set (map #(convert-property-input-string property-type %) values))
                      (catch :default e
                        (notification/show! (str e) :error false)
                        nil))
            tags-or-alias? (contains? #{:block/tags :block/alias} property-id)
            old-values (if tags-or-alias?
                         (->> (get block property-id)
                              (map (fn [e] (:block/uuid e))))
                         (get block (:db/ident property)))]
        (when (not= old-values values')
          (if tags-or-alias?
            (let [property-value-ids (map (fn [id] (:db/id (db/entity [:block/uuid id]))) values')]
              (db/transact! repo
                            [[:db/retract (:db/id block) property-id]
                             {:block/uuid block-id
                              property-id property-value-ids}]
                            {:outliner-op :save-block}))
            (if-let [msg (some #(validate-property-value schema %) values')]
              (let [msg' (str "\"" property-name "\"" " " (if (coll? msg) (first msg) msg))]
                (notification/show! msg' :warning))
              (do
                (upsert-property! repo property-id (assoc property-schema :type property-type) {})
                (let [block {:block/uuid (:block/uuid block)
                             property-id values'}]
                  (db/transact! repo [block] {:outliner-op :save-block}))))))))))

(defn- resolve-tag
  "Change `v` to a tag's UUID if v is a string tag, e.g. `#book`"
  [v]
  (when (and (string? v)
             (util/tag? (string/trim v)))
    (let [tag-without-hash (common-util/safe-subs (string/trim v) 1)
          tag (or (page-ref/get-page-name tag-without-hash) tag-without-hash)]
      (when-not (string/blank? tag)
        (let [e (db/entity [:block/name (util/page-name-sanity-lc tag)])
              e' (if e
                   (do
                     (when-not (contains? (:block/type e) "tag")
                       (db/transact! [{:db/id (:db/id e)
                                       :block/type (set (conj (:block/type e) "class"))}]))
                     e)
                   (let [m (assoc (block/page-name->map tag true)
                                  :block/type #{"class"})]
                     (db/transact! [m])
                     m))]
          (:block/uuid e'))))))

(defn set-block-property!
  [repo block-id property-id v {:keys [old-value] :as opts}]
  (let [block (db/entity repo [:block/uuid block-id])
        property (db/entity property-id)
        k-name (:block/original-name property)
        property-schema (:block/schema property)
        {:keys [type cardinality]} property-schema
        multiple-values? (= cardinality :many)
        v (or (resolve-tag v) v)]
    (if (and multiple-values? (coll? v))
      (reset-block-property-multiple-values! repo block-id property-id v opts)
      (let [v (if property v (or v ""))]
        (when (some? v)
          (let [infer-schema (when-not type (infer-schema-from-input-string v))
                property-type (or type infer-schema :default)
                schema (get (built-in-validation-schemas property) property-type)
                value (when-let [id (:db/ident property)]
                        (get block id))
                v* (if (= v :property/empty-placeholder)
                     v
                     (try
                       (convert-property-input-string property-type v)
                       (catch :default e
                         (js/console.error e)
                         (notification/show! (str e) :error false)
                         nil)))
                tags-or-alias? (and (contains? #{:block/tags :block/alias} property-id) (uuid? v*))]
            (if tags-or-alias?
              (let [property-value-id (:db/id (db/entity [:block/uuid v*]))]
                (db/transact! repo
                              [[:db/add (:db/id block) property-id property-value-id]]
                              {:outliner-op :save-block}))
              (when-not (contains? (if (set? value) value #{value}) v*)
                (if-let [msg (when-not (= v* :property/empty-placeholder) (validate-property-value schema v*))]
                  (let [msg' (str "\"" k-name "\"" " " (if (coll? msg) (first msg) msg))]
                    (notification/show! msg' :warning))
                  (let [db-ident (upsert-property! repo property-id (assoc property-schema :type property-type) {})
                        status? (= :logseq.property/status (:db/ident property))
                        value (if (= value :property/empty-placeholder) [] value)
                        new-value (cond
                                    (and multiple-values? old-value
                                         (not= old-value :frontend.components.property/new-value-placeholder))
                                    (if (coll? v*)
                                      (vec (distinct (concat value v*)))
                                      (let [v (mapv (fn [x] (if (= x old-value) v* x)) value)]
                                        (if (contains? (set v) v*)
                                          v
                                          (conj v v*))))

                                    multiple-values?
                                    (let [f (if (coll? v*) concat conj)]
                                      (f value v*))

                                    :else
                                    v*)
                          ;; don't modify maps
                        new-value (if (or (sequential? new-value) (set? new-value))
                                    (if (= :coll property-type)
                                      (vec (remove string/blank? new-value))
                                      (set (remove string/blank? new-value)))
                                    new-value)
                        block (cond->
                               {:block/uuid (:block/uuid block)
                                db-ident new-value}
                                status?
                                (assoc :block/tags [:logseq.class/task]))]
                    (db/transact! repo [block] {:outliner-op :save-block})))))))))))

(defn <update-property!
  [repo property-uuid {:keys [property-name property-schema properties]}]
  {:pre [(uuid? property-uuid)]}
  (when-let [property (db/entity [:block/uuid property-uuid])]
    (p/let [type (get-in property [:block/schema :type])
            type-changed? (and type (:type property-schema) (not= type (:type property-schema)))
            property-values (db-async/<get-block-property-values repo property-uuid)]
      (when (or (not type-changed?)
                ;; only change type if property hasn't been used yet
                (and (not (ldb/built-in? (db/get-db) property)) (empty? property-values)))
        (let [tx-data (cond-> (merge {:block/uuid property-uuid} properties)
                        property-name (merge
                                       {:block/original-name property-name})
                        property-schema (assoc :block/schema
                                                 ;; a property must have a :type when making schema changes
                                               (merge {:type :default}
                                                      property-schema))
                        true outliner-core/block-with-updated-at)]
          (db/transact! repo [tx-data]
                        {:outliner-op :save-block}))))))

(defn class-add-property!
  [repo class-uuid property-id]
  (when-let [class (db/entity repo [:block/uuid class-uuid])]
    (when (contains? (:block/type class) "class")
      (let [property (db/entity property-id)
            property-type (get-in property [:block/schema :type])
            _ (upsert-property! repo property-id
                                (cond-> (:block/schema property)
                                  (some? property-type)
                                  (assoc :type property-type))
                                {})]
        (db/transact! repo
                      [[:db/add (:db/id class) :class/schema.properties property-id]]
                      {:outliner-op :save-block})))))

(defn class-remove-property!
  [repo class-uuid k-uuid]
  (when-let [class (db/entity repo [:block/uuid class-uuid])]
    (when (contains? (:block/type class) "class")
      (when-let [property (db/entity repo [:block/uuid k-uuid])]
        (when-not (ldb/built-in-class-property? (db/get-db) class property)
          (let [property-uuid (:block/uuid property)
                {:keys [properties] :as class-schema} (:block/schema class)
                new-properties (vec (distinct (remove #{property-uuid} properties)))
                class-new-schema (assoc class-schema :properties new-properties)]
            (db/transact! repo [{:db/id (:db/id class)
                                 :block/schema class-new-schema}]
                          {:outliner-op :save-block})))))))

(defn class-set-schema!
  [repo class-uuid schema]
  (when-let [class (db/entity repo [:block/uuid class-uuid])]
    (when (contains? (:block/type class) "class")
      (db/transact! repo [{:db/id (:db/id class)
                           :block/schema schema}]
                    {:outliner-op :save-block}))))

(defn batch-set-property!
  "Notice that this works only for properties with cardinality equals to `one`."
  [repo block-ids property-id v]
  (assert property-id "property-id is nil")
  (let [property (db/entity property-id)
        type (:type (:block/schema property))
        infer-schema (when-not type (infer-schema-from-input-string v))
        property-type (or type infer-schema :default)
        {:keys [cardinality]} (:block/schema property)
        status? (= :logseq.property/status (:db/ident property))
        txs (mapcat
             (fn [id]
               (when-let [block (db/entity [:block/uuid id])]
                 (when (and (some? v) (not= cardinality :many))
                   (when-let [v* (try
                                   (convert-property-input-string property-type v)
                                   (catch :default e
                                     (notification/show! (str e) :error false)
                                     nil))]
                     [{:block/uuid (:block/uuid block)
                       property-id v*}
                      (when status?
                        [:db/add (:db/id block) :block/tags :logseq.class/task])]))))
             block-ids)]
    (when (seq txs)
      (db/transact! repo txs {:outliner-op :save-block}))))

(defn batch-remove-property!
  [repo block-ids property-id]
  (when-let [property (db/entity property-id)]
    (let [txs (mapcat
               (fn [id]
                 (when-let [block (db/entity [:block/uuid id])]
                   (when (get block property-id)
                     (let [value (get block property-id)
                           block-value? (and (= :default (get-in property [:block/schema :type] :default))
                                             (uuid? value))
                           property-block (when block-value? (db/entity [:block/uuid value]))
                           retract-blocks-tx (when (and property-block
                                                        (some? (get property-block :logseq.property/created-from-block))
                                                        (some? (get property-block :logseq.property/created-from-property)))
                                               (let [txs-state (atom [])]
                                                 (outliner-core/delete-block repo
                                                                             (db/get-db false)
                                                                             txs-state
                                                                             (outliner-core/->Block property-block)
                                                                             {:children? true})
                                                 @txs-state))]
                       (concat
                        [[:db/retract (:db/id block) property-id]]
                        retract-blocks-tx)))))
               block-ids)]
      (when (seq txs)
        (db/transact! repo txs {:outliner-op :save-block})))))

(defn remove-block-property!
  [repo block-id property-id]
  (if (contains? #{:block/alias :block/tags} property-id)
    (when-let [block (db/entity [:block/uuid block-id])]
      (db/transact! repo
                    [[:db/retract (:db/id block) property-id]]
                    {:outliner-op :save-block}))
    (batch-remove-property! repo [block-id] property-id)))

(defn delete-property-value!
  "Delete value if a property has multiple values"
  [repo block property-id property-value]
  (when (and block (uuid? property-id))
    (when (not= property-id (:block/uuid block))
      (when-let [property (db/pull [:block/uuid property-id])]
        (let [schema (:block/schema property)
              db-ident (:db/ident property)
              property-id (:db/ident property)
              tags-or-alias? (and (contains? #{:block/tags :block/alias} db-ident)
                                  (uuid? property-value))]
          (if tags-or-alias?
            (let [property-value-id (:db/id (db/entity [:block/uuid property-value]))]
              (when property-value-id
                (db/transact! repo
                              [[:db/retract (:db/id block) db-ident property-value-id]]
                              {:outliner-op :save-block})))
            (if (= :many (:cardinality schema))
              (db/transact! repo
                            [[:db/retract (:db/id block) property-id]]
                            {:outliner-op :save-block})
              (if (= :default (get-in property [:block/schema :type]))
                (set-block-property! repo (:block/uuid block)
                                     (:block/original-name property)
                                     ""
                                     {})
                (remove-block-property! repo (:block/uuid block) property-id)))))))))

(defn replace-key-with-id
  "Notice: properties need to be created first"
  [m]
  (zipmap
   (map (fn [k]
          (if (uuid? k)
            k
            (let [property-id (db-pu/get-user-property-uuid k)]
              (when-not property-id
                (throw (ex-info "Property not exists yet"
                                {:key k})))
              property-id)))
        (keys m))
   (vals m)))

(defn collapse-expand-property!
  "Notice this works only if the value itself if a block (property type should be either :default or :template)"
  [repo block property collapse?]
  (let [f (if collapse? :db/add :db/retract)]
    (db/transact! repo
                  [[f (:db/id block) :block/collapsed-properties (:db/id property)]]
                  {:outliner-op :save-block})))

(defn- get-namespace-parents
  [tags]
  (let [tags' (filter (fn [tag] (contains? (:block/type tag) "class")) tags)
        *namespaces (atom #{})]
    (doseq [tag tags']
      (when-let [ns (:block/namespace tag)]
        (loop [current-ns ns]
          (when (and
                 current-ns
                 (contains? (:block/type ns) "class")
                 (not (contains? @*namespaces (:db/id ns))))
            (swap! *namespaces conj current-ns)
            (recur (:block/namespace current-ns))))))
    @*namespaces))

(defn get-block-classes-properties
  [eid]
  (let [block (db/entity eid)
        classes (->> (:block/tags block)
                     (sort-by :block/name)
                     (filter (fn [tag] (contains? (:block/type tag) "class"))))
        namespace-parents (get-namespace-parents classes)
        all-classes (->> (concat classes namespace-parents)
                         (filter (fn [class]
                                   (seq (:properties (:block/schema class))))))
        all-properties (-> (mapcat (fn [class]
                                     (seq (:properties (:block/schema class)))) all-classes)
                           distinct)]
    {:classes classes
     :all-classes all-classes           ; block own classes + parent classes
     :classes-properties all-properties}))

(defn- closed-value-other-position?
  [property-id block]
  (and
   (some? (get block property-id))
   (let [schema (:block/schema (db/entity property-id))]
     (= (:position schema) "block-beginning"))))

(defn get-block-other-position-properties
  [eid]
  (let [block (db/entity eid)
        own-properties (filter db-property/property? (keys block))]
    (->> (:classes-properties (get-block-classes-properties eid))
         (concat own-properties)
         (filter (fn [id] (closed-value-other-position? id block)))
         (distinct))))

(defn block-has-viewable-properties?
  [block-entity]
  (let [properties (->> (keys block-entity) (filter db-property/property?))]
    (or
     (seq (:block/alias block-entity))
     (and (seq properties)
          (not= properties [:logseq.property/icon])))))

(defn property-create-new-block
  [block property value parse-block]
  (let [current-page-id (:block/uuid (or (:block/page block) block))
        page-name (str "$$$" current-page-id)
        page-entity (db/entity [:block/name page-name])
        page (or page-entity
                 (-> (block/page-name->map page-name true)
                     (assoc :block/type #{"hidden"}
                            :block/format :markdown
                            :logseq.property/source-page-id current-page-id)))
        page-tx (when-not page-entity page)
        page-id [:block/uuid (:block/uuid page)]
        parent-id (db/new-block-id)
        parent (-> {:block/uuid parent-id
                    :block/format :markdown
                    :block/content ""
                    :block/page page-id
                    :block/parent page-id
                    :block/left (or (when page-entity (model/get-block-last-direct-child-id (db/get-db) (:db/id page-entity)))
                                    page-id)
                    :logseq.property/created-from-block [:block/uuid (:block/uuid block)]
                    :logseq.property/created-from-property [:block/uuid (:block/uuid property)]}
                   sqlite-util/block-with-timestamps)
        child-1-id (db/new-block-id)
        child-1 (-> {:block/uuid child-1-id
                     :block/format :markdown
                     :block/content value
                     :block/page page-id
                     :block/parent [:block/uuid parent-id]
                     :block/left [:block/uuid parent-id]}
                    sqlite-util/block-with-timestamps
                    parse-block)]
    {:page page-tx
     :blocks [parent child-1]}))

(defn create-property-text-block!
  [block property value parse-block {:keys [class-schema?]}]
  (let [repo (state/get-current-repo)
        {:keys [page blocks]} (property-create-new-block block property value parse-block)
        first-block (first blocks)
        last-block-id (:block/uuid (last blocks))
        class? (contains? (:block/type block) "class")
        property-key (:block/original-name property)]
    (db/transact! repo (if page (cons page blocks) blocks) {:outliner-op :insert-blocks})
    (let [result (when property-key
                   (if (and class? class-schema?)
                     (class-add-property! repo (:block/uuid block) property-key)
                     (set-block-property! repo (:block/uuid block) property-key (:block/uuid first-block) {})))]
      {:last-block-id last-block-id
       :result result})))

(defn property-create-new-block-from-template
  [block property template]
  (let [current-page-id (:block/uuid (or (:block/page block) block))
        page-name (str "$$$" current-page-id)
        page-entity (db/entity [:block/name page-name])
        page (or page-entity
                 (-> (block/page-name->map page-name true)
                     (assoc :block/type #{"hidden"}
                            :block/format :markdown
                            :logseq.property/source-page-id current-page-id)))
        page-tx (when-not page-entity page)
        page-id [:block/uuid (:block/uuid page)]
        block-id (db/new-block-id)
        new-block (-> {:block/uuid block-id
                       :block/format :markdown
                       :block/content ""
                       :block/tags #{(:db/id template)}
                       :block/page page-id
                       :block/parent page-id
                       :block/left (or (when page-entity (model/get-block-last-direct-child-id (db/get-db) (:db/id page-entity)))
                                       page-id)
                       :logseq.property/created-from-block [:block/uuid (:block/uuid block)]
                       :logseq.property/created-from-property [:block/uuid (:block/uuid property)]
                       :logseq.property/created-from-template [:block/uuid (:block/uuid template)]}
                      sqlite-util/block-with-timestamps)]
    {:page page-tx
     :blocks [new-block]}))

(defn- get-property-hidden-page
  [property]
  (let [page-name (str db-property-util/hidden-page-name-prefix (:block/uuid property))]
    (or (db/entity [:block/name page-name])
        (db-property-util/build-property-hidden-page property))))

(defn re-init-commands!
  "Update commands after task status and priority's closed values has been changed"
  [property]
  (when (contains? #{:logseq.property/status :logseq.property/priority} (:db/ident property))
    (state/pub-event! [:init/commands])))

(defn replace-closed-value
  [property new-id old-id]
  (assert (and (uuid? new-id) (uuid? old-id)))
  (let [schema (-> (:block/schema property)
                   (update :values (fn [values]
                                     (vec (conj (remove #{old-id} values) new-id)))))]
    (db/transact! (state/get-current-repo)
                  [{:db/id (:db/id property)
                    :block/schema schema}]
                  {:outliner-op :save-block})))

(defn upsert-closed-value
  "id should be a block UUID or nil"
  [property {:keys [id value icon description]
             :or {description ""}}]
  (assert (or (nil? id) (uuid? id)))
  (let [property-type (get-in property [:block/schema :type] :default)]
    (when (contains? db-property-type/closed-value-property-types property-type)
      (let [property (db/entity (:db/id property))
            value (if (string? value) (string/trim value) value)
            property-schema (:block/schema property)
            closed-values (:values property-schema)
            block-values (map (fn [id] (db/entity [:block/uuid id])) closed-values)
            resolved-value (try
                             (convert-property-input-string (:type property-schema) value)
                             (catch :default e
                               (js/console.error e)
                               (notification/show! (str e) :error false)
                               nil))
            block (when id (db/entity [:block/uuid id]))
            value-block (when (uuid? value) (db/entity [:block/uuid value]))
            validate-message (validate-property-value
                              (get (built-in-validation-schemas property {:new-closed-value? true}) property-type)
                              resolved-value)]
        (cond
          (some (fn [b] (and (= resolved-value (or (db-pu/property-value-when-closed b)
                                                   (:block/uuid b)))
                             (not= id (:block/uuid b)))) block-values)
          (do
            (notification/show! "Choice already exists" :warning)
            :value-exists)

          validate-message
          (do
            (notification/show! validate-message :warning)
            :value-invalid)

          (nil? resolved-value)
          nil

          (:block/name value-block)             ; page
          (let [new-values (vec (conj closed-values value))]
            {:block-id value
             :tx-data [{:db/id (:db/id property)
                        :block/schema (assoc property-schema :values new-values)}]})

          :else
          (let [block-id (or id (db/new-block-id))
                icon (when-not (and (string? icon) (string/blank? icon)) icon)
                description (string/trim description)
                description (when-not (string/blank? description) description)
                tx-data (if block
                          [(let [schema (assoc (:block/schema block)
                                               :value resolved-value)]
                             (cond->
                              {:block/uuid id
                               :block/schema (if description
                                               (assoc schema :description description)
                                               (dissoc schema :description))}
                               icon
                               (assoc :logseq.property/icon icon)))]
                          (let [page (get-property-hidden-page property)
                                page-tx (when-not (e/entity? page) page)
                                page-id [:block/uuid (:block/uuid page)]
                                new-block (db-property-util/build-closed-value-block block-id resolved-value page-id property {:icon icon
                                                                                                                               :description description})
                                new-values (vec (conj closed-values block-id))]
                            (->> (cons page-tx [new-block
                                                {:db/id (:db/id property)
                                                 :block/schema (merge {:type property-type}
                                                                      (assoc property-schema :values new-values))}])
                                 (remove nil?))))]
            {:block-id block-id
             :tx-data tx-data}))))))

(defn <add-existing-values-to-closed-values!
  "Adds existing values as closed values and returns their new block uuids"
  [property values]
  (when (seq values)
    (let [values' (remove string/blank? values)
          property-schema (:block/schema property)]
      (if (every? uuid? values')
        (p/let [new-value-ids (vec (remove #(nil? (db/entity [:block/uuid %])) values'))]
          (when (seq new-value-ids)
            (let [property-tx {:db/id (:db/id property)
                               :block/schema (assoc property-schema :values new-value-ids)}]
              (db/transact! (state/get-current-repo) [property-tx]
                            {:outliner-op :insert-blocks})
              new-value-ids)))
        (p/let [property-id (:db/ident property)
                page (get-property-hidden-page property)
                page-tx (when-not (e/entity? page) page)
                page-id (:block/uuid page)
                closed-value-blocks (map (fn [value]
                                           (db-property-util/build-closed-value-block
                                            (db/new-block-id)
                                            value
                                            [:block/uuid page-id]
                                            property
                                            {}))
                                         values')
                value->block-id (zipmap
                                 (map #(get-in % [:block/schema :value]) closed-value-blocks)
                                 (map :block/uuid closed-value-blocks))
                new-value-ids (mapv :block/uuid closed-value-blocks)
                property-tx {:db/id (:db/id property)
                             :block/schema (assoc property-schema :values new-value-ids)}
                property-values (db-async/<get-block-property-values (state/get-current-repo) (:block/uuid property))
                block-values (->> property-values
                                  (remove #(uuid? (first %))))
                tx-data (concat
                         (when page-tx [page-tx])
                         closed-value-blocks
                         [property-tx]
                         (mapcat (fn [[id value]]
                                   [[:db/retract id property-id]
                                    {:db/id id
                                     property-id (if (set? value)
                                                   (set (map value->block-id value))
                                                   (get value->block-id value))}])
                                 (filter second block-values)))]
          (db/transact! (state/get-current-repo) tx-data
                        {:outliner-op :insert-blocks})
          new-value-ids)))))

(defn delete-closed-value!
  "Returns true when deleted or if not deleted displays warning and returns false"
  [db property value-block]
  (cond
    (ldb/built-in? db value-block)
    (do (notification/show! "The choice can't be deleted because it's built-in." :warning)
        false)

    (seq (:block/_refs value-block))
    (do (notification/show! "The choice can't be deleted because it's still used." :warning)
        false)

    :else
    (let [property (db/entity (:db/id property))
          schema (:block/schema property)
          tx-data [[:db/retractEntity (:db/id value-block)]
                   {:db/id (:db/id property)
                    :block/schema (update schema :values
                                          (fn [values]
                                            (vec (remove #{(:block/uuid value-block)} values))))}]]
      (p/do!
       (db/transact! tx-data)
       (re-init-commands! property)
       true))))

(defn get-property-block-created-block
  "Get the root block and property that created this property block."
  [eid]
  (let [b (db/entity eid)
        parents (model/get-block-parents (state/get-current-repo) (:block/uuid b) {})
        [created-from-block created-from-property]
        (some (fn [block]
                (let [from-block (:logseq.property/created-from-block block)
                      from-property (:logseq.property/created-from-property block)]
                  (when (and from-block from-property)
                    [from-block from-property]))) (reverse parents))]
    {:from-block-id (or (:db/id created-from-block) (:db/id b))
     :from-property-id (:db/id created-from-property)}))

(defn batch-set-property-closed-value!
  [block-ids db-ident closed-value]
  (let [repo (state/get-current-repo)
        closed-value-id (:block/uuid (pu/get-closed-value-entity-by-name db-ident closed-value))]
    (when closed-value-id
      (batch-set-property! repo
                           block-ids
                           db-ident
                           closed-value-id))))
