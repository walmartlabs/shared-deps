(ns shared-deps.plugin
  "Modifies the project by reading the :dependency-sets key
  and merging in the appropriate dependencies specified in the
  dependencies.edn file."
  (:require [leiningen.core.main :as main]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [clojure.edn :as edn]
            [medley.core :as medley]
            [com.stuartsierra.dependency :as d])
  (:import (java.io PushbackReader)))

(defn- find-dependencies-file
  [{root-path :root
    project-name :name}]
  (main/debug (format "Seaching for shared dependencies of project %s, starting in `%s'." project-name root-path))
  (loop [dir (io/file root-path)]
    (cond-let
      (nil? dir)
      (main/warn "Unable to find dependencies.edn file in directory `%s', or any parent directory." root-path)

      [f (io/file dir "dependencies.edn")]

      (.exists f)
      f

      :else
      (recur (.getParentFile dir)))))

(defn- read-dependencies-file*
  [file]
  (main/debug (format "Reading shared dependencies from `%s'." file))
  (with-open [s (io/reader file)]
    ;; Allow the values for each dependency set to be a vector, which is expanded
    ;; to a map with key :dependencies
    (medley/map-vals
      #(if (vector? %)
        {:dependencies %}
        %)
      (-> s
          PushbackReader.
          edn/read))))

(def ^:private read-dependencies-file (memoize read-dependencies-file*))

(defn- read-shared-dependencies [project]
  (when-let [dependencies-file (find-dependencies-file project)]
    (read-dependencies-file dependencies-file)))


(defn- order-sets-by-dependency
  "Builds a graph of the dependencies of the sets, used to order
  them when creating artifact dependencies in the project map.

  Each set may have an :extends key in the shared dependencies map.
  This is is used to set dependency set ordering.

  Returns an ordered seq of set names reflecting dependencies and
  additional dependency sets added due to :extends."
  [shared-dependencies set-ids]
  (loop [set-queue set-ids
         visited? #{}
         graph (d/graph)]
    (cond-let

      (empty? set-queue)
      (->> graph
           d/topo-sort
           (remove nil?))

      [[set-id & more-set-ids] set-queue]

      (visited? set-id)
      (recur more-set-ids visited? graph)

      ;; this set name has been visited; this is true even
      ;; if the set name is invalid (not present in the shared dependencies).
      [visited?' (conj visited? set-id)
       dependency-set (get shared-dependencies set-id)]

      (nil? dependency-set)
      (do
        (main/warn (format "No such shared dependency set %s; defined sets: %s."
                           set-id
                           (->> shared-dependencies keys (map str) sort (str/join ", "))))
        (recur more-set-ids visited?' graph))

      [extends (:extends dependency-set)]

      (nil? extends)
      (recur more-set-ids
             visited?'
             (d/depend graph set-id nil))

      :else
      (recur (into more-set-ids extends)
             visited?'
             (reduce #(d/depend %1 set-id %2) graph extends)))))

(defn- apply-set
  [shared-dependencies project set-id]
  ;; TODO: warning when dependency set not found
  ;; Using update-in for compatibility with older version of Clojure.
  ;; Convert it to update at some point in future.
  (update-in project [:dependencies]
             into (get-in shared-dependencies [set-id :dependencies])))

(defn- apply-sets
  [project sets shared-dependencies]
  (main/debug (format "Applying dependency sets %s to project %s."
                      (str/join ", " sets)
                      (:name project)))
  ;; Since order counts, and what we get is usually some flavor of list (or lazy
  ;; seq), convert dependencies into a vector first.
  (-> (reduce (partial apply-set shared-dependencies)
              (update-in project [:dependencies] vec)
              (order-sets-by-dependency shared-dependencies sets))
      ;; There's any number of ways that we can end up with duplicated lines
      ;; including that the middleware is invoked for each profile.
      ;; So we clean the dependencies.
      (update-in [:dependencies]
                 (comp vec distinct))))

(defn- extend-project-with-sets [project set-ids]
  (or (if-let [shared-dependencies (read-shared-dependencies project)]
        (apply-sets project set-ids shared-dependencies))
      ;; Any prior stage can return nil on a failure, and we'll just return
      ;; the project unchanged.
      project))

;; Keyed on project name, will be true once the warning has
;; been output (middleware gets called multiple times even in the same
;; project, based on the application of profiles).
(def ^:private warning-output (atom {}))

(defn middleware
  "The middleware invoked (multiple times!) by Leiningen, to extend and modify
  the project description. This middleware is triggered by the :dependency-sets
  key, and modifies the :dependencies key."
  [project]
  (if-let [set-ids (-> project :dependency-sets seq)]
    (extend-project-with-sets project set-ids)
    (let [project-name (:name project)]
      (when-not (get @warning-output project-name)
        (swap! warning-output assoc project-name true)
        (main/warn (format
                     "Project %s should specify a list of dependency set ids in its :dependency-sets key."
                     project-name)))
      project)))