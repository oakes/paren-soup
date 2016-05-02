(defproject paren-soup "1.9.2-SNAPSHOT"
  :description "A viewer and editor for ClojureScript"
  :url "https://github.com/oakes/paren-soup"
  :license {:name "Public Domain"
            :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[mistakes-were-made "1.6.0"]
                 [tag-soup "1.1.5"]
                 [html-soup "1.0.0"]
                 [cross-parinfer "1.0.3"]
                 [org.clojars.oakes/rangy-core "1.3.0-0"]
                 [org.clojars.oakes/rangy-textrange "1.3.0-0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/core.async "0.2.374"
                  :exclusions [org.clojure/tools.reader]]
                 [prismatic/schema "0.4.3"]]
  :profiles {:uberjar {:prep-tasks ["compile" ["cljsbuild" "once"]]}}
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :plugins [[lein-cljsbuild "1.1.2"]]
  :cljsbuild {:builds {:main {:source-paths ["src"]
                              :compiler {:output-to "resources/public/paren-soup.js"
                                         :optimizations :advanced
                                         :pretty-print false}
                              :jar true}}}
  :main paren-soup.core)
