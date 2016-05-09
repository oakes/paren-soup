(defproject paren-soup "1.10.1-SNAPSHOT"
  :description "A viewer and editor for ClojureScript"
  :url "https://github.com/oakes/paren-soup"
  :license {:name "Public Domain"
            :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[mistakes-were-made "1.6.1"]
                 [html-soup "1.1.1"]
                 [cross-parinfer "1.1.2"]
                 [cljsjs/rangy-core "1.3.0-0"]
                 [cljsjs/rangy-textrange "1.3.0-0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"]
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
