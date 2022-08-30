(ns logseq.graph-parser.text
  (:require ["path" :as path]
            [goog.string :as gstring]
            [clojure.string :as string]
            [clojure.set :as set]
            [logseq.graph-parser.mldoc :as gp-mldoc]
            [logseq.graph-parser.util :as gp-util]
            [logseq.graph-parser.property :as gp-property]
            [logseq.graph-parser.util.page-ref :as page-ref :refer [right-brackets]]))

(defn get-file-basename
  [path]
  (when-not (string/blank? path)
    ;; Same as util/node-path.name
    (.-name (path/parse (string/replace path "+" "/")))))

(def page-ref-re-0 #"\[\[(.*)\]\]")
(def org-page-ref-re #"\[\[(file:.*)\]\[.+?\]\]")
(def markdown-page-ref-re #"\[(.*)\]\(file:.*\)")

(defn get-page-name
  "Extracts page names from format-specific page-refs e.g. org/md specific and
  logseq page-refs. Only call in contexts where format-specific page-refs are
  used. For logseq page-refs use page-ref/get-page-name"
  [s]
  (and (string? s)
       (or (when-let [[_ label _path] (re-matches markdown-page-ref-re s)]
             (string/trim label))
           (when-let [[_ path _label] (re-matches org-page-ref-re s)]
             (some-> (get-file-basename path)
                     (string/replace "." "/")))
           (-> (re-matches page-ref-re-0 s)
               second))))

(defn page-ref-un-brackets!
  [s]
  (or (get-page-name s) s))

(defn get-nested-page-name
  [page-name]
  (when-let [first-match (re-find page-ref/page-ref-without-nested-re page-name)]
    (second first-match)))

(def markdown-link #"\[([^\[]+)\](\(.*\))")

(defn- remove-level-space-aux!
  [text pattern space? trim-left?]
  (let [pattern (gstring/format
                 (if space?
                   "^[%s]+\\s+"
                   "^[%s]+\\s?")
                 pattern)
        text (if trim-left? (string/triml text) text)]
    (string/replace-first text (re-pattern pattern) "")))

(defn remove-level-spaces
  ([text format block-pattern]
   (remove-level-spaces text format block-pattern false true))
  ([text format block-pattern space?]
   (remove-level-spaces text format block-pattern space? true))
  ([text format block-pattern space? trim-left?]
   (when format
     (cond
       (string/blank? text)
       ""

       (and (= "markdown" (name format))
            (string/starts-with? text "---"))
       text

       :else
       (remove-level-space-aux! text block-pattern space? trim-left?)))))

(defn namespace-page?
  [p]
  (and (string? p)
       (string/includes? p "/")
       (not (string/starts-with? p "../"))
       (not (string/starts-with? p "./"))
       (not (gp-util/url? p))))

(defonce non-parsing-properties
  (atom #{"background-color" "background_color"}))

(defn parse-non-string-property-value
  "Return parsed non-string property value or nil if none is found"
  [v]
  (cond
    (= v "true")
    true

    (= v "false")
    false

    (re-find #"^\d+$" v)
    (parse-long v)))

(def ^:private page-ref-or-tag-re
  (re-pattern (str "#?" (page-ref/->page-ref-re-str "(.*?)") "|"
                   ;; Don't capture punctuation at end of a tag
                   "#([\\S]+[^\\s.!,])")))

(defn- extract-refs-from-mldoc-ast
  [v]
  (->> v
       (remove gp-mldoc/ast-link?)
       (keep
        (fn [[typ data]]
          (case typ
            "Link"
            (case (first (:url data))
              "Page_ref"
              (second (:url data))

              "Search"
              (second (:url data))

              nil)

            "Nested_link"
            (page-ref/get-page-name (:content data))

            "Tag"
            (second (first data))

            nil)))
       (map string/trim)
       (set)))

(defn parse-property
  "Property value parsing that takes into account built-in properties, and user config"
  [k v mldoc-ast config-state]
  (let [refs (extract-refs-from-mldoc-ast mldoc-ast)
        k (if (or (symbol? k) (keyword? k)) (subs (str k) 1) k)
        v (if (or (symbol? v) (keyword? v))
            (subs (str v) 1)
            (str v))
        v (string/trim v)
        non-string-property (parse-non-string-property-value v)]
    (cond
      (contains? (set/union
                  #{"filters" "macro"}
                  (get config-state :ignored-page-references-keywords)) k)
      v

      (string/blank? v)
      nil

      (seq refs)
      refs

      non-string-property
      non-string-property

      (and (= k "file-path")
           (string/starts-with? v "file:"))
      v

      :else
      v)))
