(ns leiningen.viz-deps
  "Visualize your dependencies using Graphviz."
  (:use clojure.repl
        clojure.pprint)
  (:require [leiningen.core.main :as main]
            [shared-deps.plugin :as plugin]
            [clojure.java.browse :refer [browse-url]]
            [dorothy.core :as d]))

(defn- project-label [group project-name version]
  (if group
    (format "%s/%s%n%s" group project-name version)
    (format "%s%n%s" project-name version)))

(defn- merge-project-data
  [profiles project key]
  (into (set (get project key))
        (mapcat #(get-in project [:profiles % key])
                profiles)))

(defn- dependency->graph-id
  "For a dependency, extracts and normalizes the artifact name symbol."
  [dependency]
  (let [[artifact-name] dependency]
    (str "dep-" artifact-name)))

(defn- set->graph-id
  [set-id]
  (if (= set-id ::root)
    set-id
    (str "set-" (name set-id))))

(defn- set->graph-node
  [set-id]
  [(set->graph-id set-id) {:label (str set-id)
                           :shape :trapezium}])

(defn- dependency->label
  [dependency]
  (let [[artifact-name version] dependency]
    (format "%s%n%s" artifact-name version)))

(defn- dependency->graph-node
  [dependency]
  [(dependency->graph-id dependency) {:label (dependency->label dependency)}])

(defn- direct-dependencies
  [dependencies]
  (reduce into []
          (map (fn [d]
                 ;; Add a node and an edge
                 [(dependency->graph-node d)
                  [::root (dependency->graph-id d)]])
               dependencies)))

(defn- add-set-dependencies [graph shared-deps set-ids]
  ;; This is a  bit clumsy when a dependency is both explicit and
  ;; provided via a dependency set.
  (reduce (fn [graph' set-id]
            (let [deps (get-in shared-deps [set-id :dependencies])]
              (-> graph'
                  (into (map dependency->graph-node deps))
                  (into (map #(vector (set->graph-id set-id) (dependency->graph-id %)) deps)))))
          graph
          set-ids))

(defn- add-shared-deps
  [graph profiles project shared-deps]
  (let [root-set-ids (vec (merge-project-data profiles project :dependency-sets))
        queue-init (mapv vector
                         (repeat ::root)
                         root-set-ids)]
    (loop [result graph
           ;; which sets have nodes (not just edges) in the graph?
           added-set-ids #{}
           queue queue-init]
      (if (empty? queue)
        (-> result
            (into (map set->graph-node) added-set-ids)
            (add-set-dependencies shared-deps added-set-ids))
        (let [[[from-node-id set-id] & more-queue] queue]
          (recur
            (conj result [(set->graph-id from-node-id) (set->graph-id set-id)])
            (conj added-set-ids set-id)
            (into more-queue
                  (mapv #(vector set-id %)
                        (get-in shared-deps [set-id :extends])))))))))

(defn- build-graph
  "Builds a digraph of the dependencies, ready to pass to `dot`."
  [{:keys [profiles project shared-dependencies]}]

  (let [dependencies (merge-project-data profiles project :dependencies)]
    (-> [(d/graph-attrs {:color :blue})
         [::root {:label (project-label (:group project) (:name project) (:version project)) :shape :doubleoctagon}]]
        (into (direct-dependencies dependencies))
        (add-shared-deps profiles project shared-dependencies))))

(defn- build-dot
  [project-data]
  (->> project-data
       build-graph
       d/graph
       d/dot))

(defn- show!
  [project-data]
  (-> project-data
      build-dot
      d/show!))

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
       plugin/extract-data
       build-dot
       (d/save! output-file {:format :pdf}))

   (main/info (format "Dependency graph saved to `%s'." output-file))
   (when-not (= ":no-view" view)
     (browse-url output-file))))

(def proj-data '{:profiles [:dev]
                 :shared-dependencies {:testing {:extends [:repl-help]
                                                 :dependencies [[testing "1.0"]]}
                                       :repl-help {:dependencies [[repl-help "1.3"]
                                                                  [repl-fix "1.4.0"]]}
                                       :database {:dependencies [[postgres "1.0"]]}}
                 :project
                 {:group eReceipts :name api.receipt :version "0.1.0-SNAPSHOT"
                  :dependencies [[io.aviso/pretty "0.1.20"]]
                  :dependency-sets [:database]
                  :profiles {:dev {:dependencies [[io.aviso/toolchest "0.1.3"]]
                                   :dependency-sets [:testing]}}}})

(comment
  (show! proj-data)

  (-> proj-data build-dot println)
  )