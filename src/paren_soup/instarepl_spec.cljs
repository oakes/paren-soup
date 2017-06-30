(ns paren-soup.instarepl-spec
  (:require [paren-soup.instarepl :as i]
            [clojure.spec.alpha :as s :refer [fdef]]))

(def elem? #(instance? js/Element %))

(fdef i/elems->locations
  :args (s/cat :elems (s/coll-of elem?) :top-offset number?)
  :ret (s/coll-of map?))

(fdef i/results->html
  :args (s/cat :results any? :locations (s/coll-of map?))
  :ret (s/coll-of string?))

(fdef i/get-collections
  :args (s/cat :element elem?)
  :ret (s/coll-of elem?))

(fdef i/collection->content
  :args (s/cat :elem elem?)
  :ret string?)

(fdef i/create-compiler-fn
  :args (s/cat)
  :ret fn?)

