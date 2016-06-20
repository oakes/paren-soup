(defproject paren-soup "2.0.13-SNAPSHOT"
  :description "A viewer and editor for ClojureScript"
  :url "https://github.com/oakes/paren-soup"
  :license {:name "Public Domain"
            :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[mistakes-were-made "1.6.4"]
                 [html-soup "1.2.2"]
                 [cross-parinfer "1.1.6"]
                 [cljsjs/rangy-core "1.3.0-1"]
                 [cljsjs/rangy-textrange "1.3.0-1"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]
                 [org.clojure/core.async "0.2.374"]
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
