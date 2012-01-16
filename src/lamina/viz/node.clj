;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.viz.node
  (:use
    [lamina.core walk]
    [lamina.viz core])
  (:require
    [lamina.core.node :as n]
    [lamina.core.result :as r]
    [lamina.core.lock :as l]))

;;;

(defn show-queue? [{:keys [error downstream-count consumed?]}]
  (and
    (not error)
    (or consumed? (zero? downstream-count))))

(defn message-string [messages]
  (let [cnt (count messages)
        trim? (> cnt 5)
        msgs (take (if trim? 3 5) messages)]
    (str
      (when trim?
        (str (pr-str (last messages)) "| ... |"))
      (->> msgs reverse (map pr-str) (interpose " | ") (apply str)))))

(defn edge-seq->nodes [es]
  (->> es
    (mapcat #(vector (:src %) (:dst %)))
    (remove nil?)
    distinct))

(defn queue-descriptor [n]
  (let [{:keys [messages] :as m} (node-data n)]
    (when (show-queue? m)
      {:shape :Mrecord
       :fontname (if (empty? messages) :times :helvetica)
       :label (str "{"
                (if (empty? messages)
                  "\u2205"
                  (message-string messages))
                "}")})))

(defn node-descriptor [n]
  (let [{:keys [description operator predicate? closed? error]} (node-data n)
        label (if error
                (str error)
                (or description
                  (when-not (instance? (class identity) operator)
                    (describe-fn operator))
                  ""))]
    {:label label
     :fontcolor (when error :red)
     :color (cond
              closed? :grey
              error :red
              :else nil)
     :width (when-not label 0.5)
     :fontname :helvetica
     :shape :box
     :peripheries (when predicate? 2)}))

(defn edge-descriptor [{:keys [src dst description]}]
  (let [hide-desc? (#{"join" "split" "fork"} description)
        dotted? (= "fork" description)]
    {:src src
     :dst dst
     :label (when-not hide-desc? description)
     :fontname :helvetica
     :style (when dotted? :dotted)}))

(defn graph-descriptor [root]
  (let [edges (edge-seq root)
        nodes (edge-seq->nodes edges)]
    (merge-with #(if (map? %1) (merge %1 %2) (concat %1 %2))
      ;; normal nodes and edges
      {:nodes (zipmap nodes (map node-descriptor nodes))
       :edges edges}
      ;; queue nodes and edges
      {:nodes (zipmap
                (map #(vector :queue %) nodes)
                (map queue-descriptor nodes))
       :edges (map
                #(hash-map :src %, :dst [:queue %], :arrowhead :dot)
                nodes)})))

;;;

(def node-frame (gen-frame "Lamina"))

(defn view-graph [& nodes]
  (let [descriptor (->> nodes
                     (map graph-descriptor)
                     (apply merge-with merge))
        dot-string (digraph {:rankdir :LR, :pad 0.25} descriptor)]
    (view-dot-string node-frame dot-string)))

(defn sample-graph [root msg]
  (let [edges (edge-seq root)
        readable-nodes (->> edges
                         edge-seq->nodes
                         (filter n/node?)
                         (map node-data)
                         (remove #(instance? (class identity) (:operator %)))
                         (remove show-queue?)
                         (map :node))]
    (l/acquire-all true readable-nodes)
    (let [results (zipmap readable-nodes (map n/read-node readable-nodes))]
      (n/propagate root msg true)
      (doseq [r (vals results)]
        (r/error r ::nothing-received))
      (->> results
        (filter (fn [[_ r]] (not= ::none (r/success-value r ::none))))
        (into {})))))

(defn trace-descriptor [root msg]
  (let [results (sample-graph root msg)
        descriptor (graph-descriptor root)
        edge-label (fn [e]
                     (when-let [r (results (:src e))]
                       (pr-str (r/success-value r nil))))]
    (-> descriptor
      (update-in [:nodes] assoc :msg {:label (pr-str msg), :width 0, :shape :plaintext, :fontname :helvetica})
      (update-in [:edges] (fn [edges] (map #(assoc % :label (edge-label %)) edges)))
      (update-in [:edges] conj {:src :msg :dst root}))))

(defn trace-message [root msg]
  (let [descriptor (trace-descriptor root msg)
        dot-string (digraph {:rankdir :LR, :pad 0.25} descriptor)]
    (view-dot-string node-frame dot-string)))

