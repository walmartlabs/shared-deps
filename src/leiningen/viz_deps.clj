(ns leiningen.viz-deps
  "Visualize your dependencies using Graphviz."
  (:use clojure.repl
        clojure.pprint)
  (:require [leiningen.core.main :as main]
            [shared-deps.plugin :as plugin]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [clojure.java.browse :refer [browse-url]]
            [leiningen.core.classpath :as classpath]
            [dorothy.core :as d]))

(defn- merge-project-data
  [profiles project key]
  (into (get project key)
        (mapcat #(get-in project [:profiles % key])
                profiles)))

(defn- dependency->label
  [dependency]
  (let [[artifact-name version] dependency]
    (format "%s%n%s" artifact-name version)))

(defn gen-graph-id
  [k]
  (str (gensym (str (name k) "-"))))

(defn- minimal-dependency
  "Reduces a dependency to just its first two values; symbol (for group and artifact) and
  version."
  [dependency]
  (if (= 2 (count dependency))
    dependency
    (->> dependency (take 2) vec)))


(defn- find-dependency-graph-id
  [graph dependency]
  (get-in graph [:deps (first dependency)]))

(defn- add-edge
  [graph from-graph-id to-graph-id]
  (update-in graph [:edges] conj [from-graph-id to-graph-id]))

(defn- add-node
  [graph node-id attributes]
  (assoc-in graph [:nodes node-id] (if (string? attributes)
                                     {:label attributes}
                                     attributes)))

(defn- add-dependencies
  [graph source-graph-id target-dependencies]
  (->> target-dependencies
       (map minimal-dependency)
       distinct
       (reduce (fn [graph' dependency]
                 (if-let [dep-graph-id (find-dependency-graph-id graph' dependency)]
                   (add-edge graph' source-graph-id dep-graph-id)
                   (let [dependency-key (first dependency)
                         new-dependency-graph-id (gen-graph-id dependency-key)]
                     (-> graph'
                         (assoc-in [:deps dependency-key] new-dependency-graph-id)
                         (add-node new-dependency-graph-id (dependency->label dependency))
                         (add-edge source-graph-id new-dependency-graph-id)))))
               graph)))

(defn- add-root-dependencies
  [graph profiles project]
  (add-dependencies graph :root (merge-project-data profiles project :dependencies)))

(defn- add-set
  [graph from-graph-id set-id shared-dependencies]
  (if (get-in graph [:sets set-id])
    graph                                                   ; already present
    (let [set-graph-id (gen-graph-id set-id)
          set-dependencies (get-in shared-dependencies [set-id :dependencies])
          set-extends (get-in shared-dependencies [set-id :extends])
          graph' (-> graph
                     (assoc-in [:sets set-id] set-graph-id)
                     (add-node set-graph-id {:label (str set-id)
                                             :shape :trapezium})
                     (add-edge from-graph-id set-graph-id)
                     (add-dependencies set-graph-id set-dependencies))]
      (reduce (fn [graph-a extended-set-id]
                (add-set graph-a set-graph-id extended-set-id shared-dependencies))
              graph'
              set-extends))))

(defn- add-root-sets
  [graph profiles project shared-dependencies]
  (->> (merge-project-data profiles project :dependency-sets)
       distinct
       (reduce (fn [graph' set-id]
                 (add-set graph' :root set-id shared-dependencies))
               graph)))

(defn- add-transitive-dependencies [graph project]
  (let [hierarchy (classpath/dependency-hierarchy :dependencies project)]
    (loop [graph graph
           queue (into [] hierarchy)
           processed #{}]
      (cond-let
        (empty? queue)
        graph

        [[[dependency transitives] & more-queue] queue
         dependency' (minimal-dependency dependency)]

        ;; May need to get the minimal of this
        (contains? processed dependency')
        (recur graph more-queue processed)

        :else
        (let [source-graph-id (find-dependency-graph-id graph dependency')]
          (recur (add-dependencies graph source-graph-id (keys transitives))
                 (concat more-queue transitives)
                 (conj processed dependency)))))))

(defn- dependency-graph
  "Builds out a structured dependency graph, from which a Dorothy node graph can be constructed."
  [{:keys [profiles project shared-dependencies]} merged-project]
  (let [root-dependency [(symbol (-> project :group str) (-> project :name str)) (:version project)]]
    ;; :nodes - map from node graph id to node attributes
    ;; :edges - list of edge tuples [from graph id, to graph id]
    ;; :sets - map from set id (a keyword or a symbol, typically) to a generated node graph id (a symbol)
    ;; :deps - map from artifact symbol (e.g., com.walmartlab/shared-deps) to a generated node graph id
    (-> {:nodes {:root {:label (dependency->label root-dependency)
                        :shape :doubleoctagon}}
         :edges []
         :sets {}
         :deps {}}
        (add-root-dependencies profiles project)
        (add-root-sets profiles project shared-dependencies)
        (add-transitive-dependencies merged-project))))

(defn- node-graph
  [dependency-graph]
  (reduce into
          [(d/graph-attrs {:rankdir :LR})]
          [(for [[k v] (:nodes dependency-graph)]
             [k v])
           (:edges dependency-graph)]))

(defn- build-dot
  [project]
  (-> (dependency-graph (plugin/extract-data project) project)
      node-graph
      d/digraph
      d/dot))

(def ^:private output-file "target/dependencies.pdf")

(defn viz-deps
  "Generate a visualization of this project's dependencies.

  Normally, the output file is opened automatically; this can be
  prevented with the :no-view argument. "
  ([project]
   (viz-deps project nil))
  ([project view]
   (when-not (#{nil ":no-view"} view)
     (main/warn "view should be omitted, or :no-view to avoid opening the generated dependency graph."))

   (-> project
       build-dot
       (d/save! output-file {:format :pdf}))

   (main/info (format "Dependency graph saved to `%s'." output-file))
   (when-not (= ":no-view" view)
     (browse-url output-file))))
