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
            [robert.hooke :as hooke]
            [leiningen.pom :as pom])
  (:import (java.io PushbackReader File)))

(defn- project-name
  [{project-group :group
    project-name :name}]
  (if project-group
    (str project-group "/" project-name)
    project-name))

(defn- find-dependencies-file
  [{root-path :root
    :as project}]
  (main/debug (format "Seaching for shared dependencies of project %s, starting in `%s'." (project-name project) root-path))
  (loop [dir (io/file root-path)]
    (cond-let
      (nil? dir)
      (main/warn "Unable to find dependencies.edn file in directory `%s', or any parent directory." root-path)

      [f (io/file dir "dependencies.edn")]

      (.exists f)
      f

      :else
      (recur (.getParentFile dir)))))

(def ^:private read-dependencies-file
  (memoize
   (fn [file]
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
            edn/read))))))

(defn- read-raw [^File f]
  (main/debug (format "Reading project file `%s'." f))
  (try
    ;; Apparently, the path for read-raw has to be a string, not a File.
    (project/read-raw (.getAbsolutePath f))
    (catch Throwable t
      (main/warn (.getMessage t) t))))

(def ^:private build-sibling-dependencies-map
  (memoize
   (fn [root-dir root-project-file]
     (let [root-project (read-raw root-project-file)]
       (->> root-project
            :sub
            (reduce (fn [deps module-path]
                      (if-let [project (read-raw (io/file root-dir module-path "project.clj"))]
                        (let [{project-name :name
                               project-group :group
                               version :version} project
                              name-symbol (symbol project-group project-name)]
                          (assoc-in deps [name-symbol :dependencies]
                                    ;; The value is just like an entry in the
                                    ;; dependencies.edn file: a vector of dependency
                                    ;; specs (each a vector).
                                    [[name-symbol version]]))
                        deps))
                    {}))))))


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

(defn- read-shared-dependencies
  [project]
  (let [sibling-dependencies (build-sibling-project-dependencies project)
        shared-dependencies (some-> (find-dependencies-file project)
                                    read-dependencies-file)]
    (merge sibling-dependencies shared-dependencies)))

(defn- ->id-list
  [ids]
  (->> ids
       (map str)
       sort
       (interleave (repeat "\n - "))
       (apply str)))

(defn- pad-to [s width]
  (let [l (.length s)]
    (if (= l width)
      s
      (apply str s
             (repeat (- width l) " ")))))

(def ^:private desired-width
  "This is somewhat arbitrary."
  120)

(defn- ->long-id-list
  "Somewhat like [[->id-list]], but formats the results into three columns of equal widths."
  [ids]
  (let [sorted (->> ids
                    (map str)
                    sort)
        longest (->> sorted
                     (map #(.length %))
                     (reduce max))
        id-count (count sorted)
        columns (-> (/ desired-width (+ 2 longest))
                    Math/floor
                    long)
        rows (-> id-count
                 (/ columns)
                 Math/ceil
                 long)]
    (with-out-str
      (doseq [row (range 0 rows)
              col (range 0 columns)
              :let [i (+ (* col rows) row)]
              :when (< i (dec id-count))
              :let [id (nth sorted i)]]
        (when (and (pos? row)
                   (zero? col))
          (println))
        (print "  "
               (if (= col (dec columns))
                 id
                 (pad-to id longest)))))))

(defn- report-unknown-dependencies
  [project unknown-ids shared-dependencies]
  (let [n (count unknown-ids)]
    (when (pos? n)
      (main/warn
        (apply str
               (format "Dependency error in project `%s':\n"
                       (project-name project))
               (if (= (count unknown-ids) 1)
                 (format "The dependency id `%s' is not defined." (first unknown-ids))
                 (str "The following dependency ids are not defined:"
                      (->id-list unknown-ids)))
               "\nAvailable dependency ids:\n"
               (-> shared-dependencies keys ->long-id-list))))))

(defn- order-sets-by-dependency
  "Builds a graph of the dependencies of the sets, used to order
  them when creating artifact dependencies in the project map.

  Each set may have an :extends key in the shared dependencies map.
  This is is used to set dependency set ordering.

  Returns an ordered seq of set names reflecting dependencies and
  additional dependency sets added due to :extends."
  [project shared-dependencies set-ids]
  (loop [set-queue set-ids
         unknown-ids #{}
         visited? #{}
         graph (d/graph)]
    (cond-let

      (empty? set-queue)
      (do
        (report-unknown-dependencies project unknown-ids shared-dependencies)
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
  [project set-id shared-dependencies dependencies-ks]
  (update-in project dependencies-ks
             into (get-in shared-dependencies [set-id :dependencies])))

(def ^:private vec-distinct (comp vec distinct))

(defn- apply-sets
  [project profile shared-dependencies]
  (let [base-ks (if profile
                  [:profiles profile]
                  [])
        dependencies-ks (conj base-ks :dependencies)
        sets-ks (conj base-ks :dependency-sets)
        ;; By the time the middleware is invoked, the :dependency-sets key has
        ;; been merged. We want to see the raw version, from before profiles
        ;; are merged.
        sets (get-in (-> project meta :without-profiles) sets-ks)]
    (if (seq sets)
      (do
        (main/debug (format "Applying dependency sets %s (from %s profile) to project %s."
                            (str/join ", " sets)
                            (or profile "default")
                            (project-name project)))
        (let [project' (-> (reduce #(apply-set %1 %2 shared-dependencies dependencies-ks)
                                   ;; Since order counts, and what we get is usually some flavor of list (or lazy
                                   ;; seq), convert dependencies into a vector first.
                                   (update-in project dependencies-ks vec)
                                   (order-sets-by-dependency project shared-dependencies sets))
                           ;; There's any number of ways that we can end up with duplicated lines,
                           ;; so we clean the dependencies.
                           (update-in dependencies-ks vec-distinct))
              dependencies+ (get-in project' dependencies-ks)]
          ;; Rewrite the :without-profiles version of the project as if the dependency set
          ;; dependencies were there originally; this is necessary to make things work correctly
          ;; when using the pom task.
          (vary-meta project' assoc-in (into [:without-profiles] dependencies-ks) dependencies+)))
      project)))

(defn middleware
  "The middleware invoked by Leiningen, to extend and modify
  the project description. This middleware is triggered by the :dependency-sets
  key, and modifies the :dependencies key."
  [project]
  (let [profiles (->> project meta :included-profiles (filter keyword?) distinct)]
    (if-let [shared-dependencies (read-shared-dependencies project)]
      (do
        (main/debug (format
                      "Adding shared dependencies for project %s with profiles %s."
                      (project-name project)
                      (str/join ", " profiles)))
        (let [project' (reduce (fn [project' profile]
                                 (apply-sets project' profile shared-dependencies))
                               project
                               (into [nil] profiles))
              ;; Ok, now we need to "fold" in each profile's dependencies into
              ;; the main :dependencies; this replicates what is normally done
              ;; when normalizing a profile before the middleware is invoked.
              combined-project (reduce #(update-in %1 [:dependencies]
                                                   into
                                                   (get-in project' [:profiles %2 :dependencies]))
                                       project'
                                       profiles)]
          ;; The above may (possibly) introduce some duplication:
          (update-in combined-project [:dependencies] distinct)))
      ;; Case where dependencies file not found:
      project)))

;; This is necessary to work around a problem with the pom task; it captures
;; the original project, but in our case, that's not quite what we want.
;; Without this hook, we end up with all profile dependencies (including
;; :dev and :test) as full dependencies in the pom.xml.
;; However, this is not perfect as it leaves some built-in dependencies on
;; org.clojure/tools.nrepl and clojure-complete in the output pom.xml.

(hooke/add-hook #'pom/make-pom
                (fn [f project & rest]
                  (apply f (-> project meta :without-profiles) rest)))