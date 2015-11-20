# shared-deps

If you've worked with projects with *large* numbers of subprojects,
you may have noticed that coordinating your dependencies can get a bit
cumbersome.  On one of our main projects, we have 70 sub-projects.
Upgrading to the latest release of, say, core.async, involves too
much global replacing ... it is not *Don't Repeat Yourself*.

With this plugin, you can define *sets* of dependencies,
and store them across all-sub modules in a single `dependencies.edn` file
at the root of your project.

Each sub-module must include the shared-deps plugin, and specify
a :dependency-sets key, a list of dependency set ids.

`dependencies.edn` contains a single map: from dependency set id
to a vector of dependencies for that dependency set.

More than just coordinating artifact versions
is the challenge of maintaining the nest of exclusion rules
that can occur when trying to mix and match various third party
dependencies and their individual transitive dependencies.
Having a DRY solution here keeps the project.clj files very concise
and readable.

## Example

Your `dependencies.edn` file contains the following:

    {:clojure [[org.clojure/clojure "1.7.0"]]
     :core.async [org.clojure/core.async "0.2.371"]]
     :speclj [[speclj "3.3.1"]]
     :cljs [[org.clojure/clojurescript "1.7.170"]]}
 
Each dependency set can define any number of dependencies, including
:exclusions or other options. These dependencies are simply
appended to the standard list of dependencies provided
in `project.clj`.
 
A sub-project may define dependencies on some or all of these:
 
    (defproject my-app "0.1.0-SNAPSHOT"
      :dependencies [[org.clojure/core.match "0.2.2"]]
      :dependency-sets [:clojure
                        :core.async]
      :profiles {:dev {:dependency-sets [:speclj]}})                   

The extra dependencies are available to the REPL, tests, or other plugins, exactly
as if specified directly in `project.clj` traditionally.

## Extending Sets

Say you notice that *everywhere* that you use ClojureScript (the :cljs dependency set)
you are also using the :core.async dependency set.  That can be expressed
by changing `dependencies.edn`:

    {:clojure [[org.clojure/clojure "1.7.0"]]
     :core.async [[org.clojure/core.async "0.2.371"]]
     :speclj [[speclj "3.3.1"]]
     :cljs {:extends [:core.async]
            :dependencies [[org.clojure/clojurescript "1.7.170"]]}}

At this point, if you specify the :cljs dependency set in a sub-project, 
you get not just the :cljs artifact dependencies, but the :core.async
artifact dependencies 'for free'. 

The shared-deps plugin
treats the :cljs dependency set as a dependency of the :core.async dependency set; this means that
the :core.async artifact dependencies will be added first, and 
the :cljs artifact dependencies added later.

## Leveraging Sets

You shouldn't think of a set as a way of just specifying a single dependency;
you will likely find cases, as above with :cljs and :core.async, where you
consistently use several artifacts together. 

We regularly have complex dependency sets with ids like :logging, :testing,
or :database.

## Id Conventions

The dependency set ids do not have to be keywords; they can be strings,
symbols, or any type.

Our convention is to use symbols for cross-project dependencies (dependencies
between modules within the same umbrella project), and keywords
for third party dependencies.

## Usage

Put `[shared-deps "0.2.1"]` into the `:plugins` vector of your `project.clj`.

You can verify the behavior with  `lein pprint :dependencies`; the output from
this command will be the full list of dependencies, after the shared-deps plugin
has processed all dependency sets in the active profiles.  You will need
to enable the lein-pprint plugin.

## License

Copyright © 2015 Walmart Labs

Distributed under the Apache Software License 2.0.
