(defproject paren-soup "1.9.0-SNAPSHOT"
  :description "A viewer and editor for ClojureScript"
  :url "https://github.com/oakes/paren-soup"
  :license {:name "Public Domain"
            :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[mistakes-were-made "1.5.2"]
                 [tag-soup "1.1.4"]
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
                                         :optimizations :simple
                                         :pretty-print false
                                         :foreign-libs [{:file "src/js/rangy-core.js"
                                                         :provides ["rangy.core"]}
                                                        {:file "src/js/rangy-textrange.js"
                                                         :provides ["rangy.textrange"]}
                                                        {:file "js/parinfer.js"
                                                         :provides ["parinfer.core"]}]}
                              :jar true}}}
  :main paren-soup.core)
