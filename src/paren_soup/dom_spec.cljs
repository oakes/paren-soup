(ns paren-soup.dom-spec
  (:require [paren-soup.dom :as d]
            [clojure.spec :as s :refer [fdef]]))

(def node? #(instance? js/Node %))
(def position? (s/coll-of number?))

(fdef d/get-selection
  :args (s/cat :element node? :full-selection? boolean?)
  :ret map?)

(fdef d/get-cursor-position
  :args (s/cat :element node? :full-selection? boolean?)
  :ret (s/coll-of position?))

(fdef d/set-cursor-position!
  :args (s/cat :element node? :position position?))

(fdef d/get-parent
  :args (s/cat :node node? :class-name string?)
  :ret (s/nilable node?))

(fdef d/get-parents
  :args (s/cat :node node? :class-name string?)
  :ret (s/coll-of node?))

(fdef d/text-node?
  :args (s/cat :node node?)
  :ret boolean?)

(fdef d/error-node?
  :args (s/cat :node node?)
  :ret boolean?)

(fdef d/coll-node?
  :args (s/cat :node node?)
  :ret boolean?)

(fdef d/top-level?
  :args (s/cat :node node?)
  :ret boolean?)

(fdef d/common-ancestor
  :args (s/cat :first-node node? :second-node node?)
  :ret (s/nilable node?))

(fdef d/get-focused-elem
  :args (s/cat :class-name string?))

(fdef d/get-focused-form
  :args (s/cat))

(fdef d/get-nearest-ns
  :args (s/cat :node node?)
  :ret (s/nilable symbol?))

(fdef d/get-focused-top-level
  :args (s/cat)
  :ret node?)

(fdef d/get-completion-context
  :args (s/cat :symbol-length number? :cursor-offset number?)
  :ret (s/nilable map?))

(fdef d/get-completion-info
  :args (s/cat)
  :ret (s/nilable map?))

