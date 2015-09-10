(defproject paren-soup "0.1.3-SNAPSHOT"
  :description "A viewer and editor for ClojureScript"
  :url "https://github.com/oakes/paren-soup"
  :license {:name "Public Domain"
            :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[org.clojars.oakes/tools.reader "0.10.0-SNAPSHOT"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [prismatic/schema "0.4.3"]]
  :profiles {:uberjar {:prep-tasks ["compile" ["cljsbuild" "once"]]}}
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :plugins [[lein-cljsbuild "1.1.0"]]
  :cljsbuild { 
    :builds {
      :main {
        :source-paths ["src"]
        :compiler {:output-to "resources/public/paren-soup.js"
                   :optimizations :simple
                   :pretty-print false
                   :foreign-libs [{:file "src/js/rangy-core.js"
                                   :provides ["rangy.core"]}
                                  {:file "src/js/rangy-textrange.js"
                                   :provides ["rangy.textrange"]}]}
        :jar true}}}
  :main paren-soup.core)
