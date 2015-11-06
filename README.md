# shared-deps

If you've worked with projects with *large* numbers of subprojects,
you may have noticed that coordinating your dependencies can get a bit
cumbersome.  On one of our main projects, we have 70 sub-projects.
Upgrading to the latest release of, say, core.async, involves too
much global replacing ... it is not *Don't Repeat Yourself*.

With this plugin, you can define *categories* of dependencies,
and store them across all-sub modules in a single `dependencies.edn` file
at the root of your project.

Each sub-module can include the `shared-deps` plugin, and specify
a :dependency-categories key, a list of categories.

`dependencies.edn` contains a single map: from category
to a vector of dependencies for that category.

## Example

Your `dependencies.edn` file contains the following:

    {:core.async [org.clojure/core.async "0.2.371"]]
     :speclj [[speclj "3.3.1"]]
     :cljs [[org.clojure/clojurescript "1.7.170"]]}
 
Each category can define any number of dependencies, including
:exclusions or other options. These dependencies are simply
appended to the standard list of dependencies provided
in `project.clj`.
 
A sub-project may define dependencies on some or all of these:
 
    (defproject my-app "0.1.0-SNAPSHOT"
      :dependencies [[org.clojure/clojure "1.7.0"]
                     [org.clojure/core.match "0.2.2"]]
      :dependency-categories [:core.async]
      :profiles {:dev {:dependency-categories [:speclj]}})                   

The extra dependencies are available to the REPL, tests, or other plugins, exactly
as if specified directly in `project.clj` normally.

## Extending Categories

Say you notice that *everywhere* that you use ClojureScript (the :cljs category)
you are also using the :core.async category.  That can be expressed
by changing `dependencies.edn`:

    {:core.async [[org.clojure/core.async "0.2.371"]]
     :speclj [[speclj "3.3.1"]]
     :cljs {:extends [:core.async]
            :dependencies [[org.clojure/clojurescript "1.7.170"]]}}

At this point, if you specify the :cljs category in a sub-project, 
you get not just the :cljs artifact dependencies, but the :core.async
artifact dependencies 'for free'. 

The shared-deps plugin
treats the :cljs category as a dependency of the :core.async category; this means that
the :core.async artifact dependencies will be added first, and 
the :cljs artifact dependencies added later.

## Coming Soon

Categories will be able to provide exclusion rules as well as artifact
dependencies.

## Usage

Put `[shared-deps "<version>"]` into the `:plugins` vector of your `project.clj`.

You can verify the behavior with  `lein pprint :dependencies'; the output from
this command will be the full list of dependencies, after shared-deps
has processed all categories in the active profiles.  You will need
to enable the lein-pprint plugin.

## License

Copyright © 2015 Walmart Labs

Distributed under the Apache Software License 2.0.
