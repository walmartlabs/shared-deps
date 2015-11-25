(ns shared-deps.plugin
  "Modifies the project by reading the :dependency-sets key
  and merging in the appropriate dependencies specified in the
  dependencies.edn file."
  (:require [leiningen.core.main :as main]
            [leiningen.core.project :as project]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [clojure.edn :as edn]
            [medley.core :as medley]
            [com.stuartsierra.dependency :as d]
            [clojure.pprint :refer [cl-format]])
  (:import (java.io PushbackReader File)))

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

(defn- read-dependencies-file
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


(defn- read-raw [^File f]
  (main/debug (format "Reading project file `%s'." f))
  (try
    ;; Apparently, the path for read-raw has to be a string, not a File.
    (project/read-raw (.getAbsolutePath f))
    (catch Throwable t
      (main/warn (.getMessage t) t))))

(defn- build-sibling-dependencies-map
  [root-dir root-project-file]
  (let [root-project (read-raw root-project-file)]
    (->> root-project
         :sub
         (reduce (fn [deps module-path]
                   (if-let [project (read-raw (io/file root-dir module-path "project.clj"))]
                     (let [{project-name :name
                            project-group :group
                            version :version} project
                           name-symbol (symbol project-group project-name)]
                       (assoc deps name-symbol
                                   ;; The value is just like an entry in the
                                   ;; dependencies.edn file: a vector of dependency
                                   ;; specs (each a vector).
                                   [[name-symbol version]]))
                     deps))
                 {}))))


(defn- build-sibling-project-dependencies
  "Starting from a sub-project, work upwards to the parent project.clj;
  from this, extract's the :sub key (normally used by the lein-sub plugin),
  then use this to read all the sibling project's project.clj.  From this, a map
  from project name (symbol) to dependency vector is created, using each
  project's name and version."
  [{root-path :root}]
  (loop [dir (-> root-path io/file .getParentFile)]
    (cond-let
      (nil? dir)
      (main/warn "Unable to find containing project's project.clj from directory `%s'." root-path)

      [f (io/file dir "project.clj")]

      (.exists f)
      (build-sibling-dependencies-map dir f)

      :else
      (recur (.getParentFile dir)))))

(defn- read-shared-dependencies*
  [project]
  (let [sibling-dependencies (build-sibling-project-dependencies project)
        shared-dependencies (some-> (find-dependencies-file project)
                                    read-dependencies-file)]
    (merge sibling-dependencies shared-dependencies)))

(def ^:private read-shared-dependencies (memoize read-shared-dependencies*))

(defn- ->id-list
  [ids]
  (->> ids
       (map str)
       sort
       (interleave (repeat "\n - "))
       (apply str)))

(defn- report-unknown-dependencies
  [unknown-ids shared-dependencies]
  (let [n (count unknown-ids)]
    (when (pos? n)
      (main/warn
        (apply str
               (if (= (count unknown-ids) 1)
                 (format "The dependency id `%s' is not defined." (first unknown-ids))
                 (str "The following dependency ids are not defined:"
                      (->id-list unknown-ids)))
               "\nAvailable dependency ids:"
               (-> shared-dependencies keys ->id-list))))))

(defn- order-sets-by-dependency
  "Builds a graph of the dependencies of the sets, used to order
  them when creating artifact dependencies in the project map.

  Each set may have an :extends key in the shared dependencies map.
  This is is used to set dependency set ordering.

  Returns an ordered seq of set names reflecting dependencies and
  additional dependency sets added due to :extends."
  [shared-dependencies set-ids]
  (loop [set-queue set-ids
         unknown-ids #{}
         visited? #{}
         graph (d/graph)]
    (cond-let

      (empty? set-queue)
      (do
        (report-unknown-dependencies unknown-ids shared-dependencies)
        (->> graph
             d/topo-sort
             (remove nil?)))

      [[set-id & more-set-ids] set-queue]

      (visited? set-id)
      (recur more-set-ids unknown-ids visited? graph)

      ;; this set name has been visited; this is true even
      ;; if the set name is invalid (not present in the shared dependencies).
      [visited?' (conj visited? set-id)
       dependency-set (get shared-dependencies set-id)]

      (nil? dependency-set)
      (recur more-set-ids
             (conj unknown-ids set-id)
             visited?'
             graph)

      [extends (:extends dependency-set)]

      (nil? extends)
      (recur more-set-ids
             unknown-ids
             visited?'
             (d/depend graph set-id nil))

      :else
      (recur (into more-set-ids extends)
             unknown-ids
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