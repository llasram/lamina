;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.fn
  (:use
    [lamina.core channel pipeline]
    [clojure walk]))

;;;

(def special-form?
  (set '(let if do let fn quote var throw loop recur try catch finally)))

(defn first= [symbol expr]
  (and (list? expr) (= symbol (first expr))))

(declare async)

(defn valid-expr? [expr]
  (and
    (list? expr)
    (< 1 (count expr))
    (not (special-form? (first expr)))))

(defn transform-expr [expr]
  (let [args (map #(gensym (str "arg" % "-")) (-> expr count dec range))]
    `(run-pipeline []
       ~@(map
	   (fn [arg] `(read-merge (constantly ~arg) conj))
	   (rest expr))
       (fn [[~@args]]
	 (~(first expr) ~@args)))))

(defn transform-if [[_ predicate true-clause false-clause]]
  `(run-pipeline ~predicate
     (fn [predicate#]
       (if predicate#
	 ~true-clause
	 ~@(when false-clause [false-clause])))))

(defn transform-throw [[_ exception]]
  `(run-pipeline ~exception
     (fn [exception#]
       (throw exception#))))

(defn transform-finally [transformed-body [_ & finally-exprs]]
  `(run-pipeline nil
     (pipeline 
       :error-handler
       (fn [ex#]
	 (run-pipeline nil
	   :error-handler (fn [ex##] (redirect (pipeline (fn [_] (throw ex##))) nil))
	   (fn [_]
	     ~finally-exprs)
	   (fn [_]
	     (throw ex#))))
       (fn [_]
	 ~transformed-body))
     (fn [_]
       ~finally-exprs)))

(defn transform-try [[_ & exprs]]
  (let [finally-clause (when (->> exprs last (first= 'finally)) (last exprs))
	exprs (if finally-clause (butlast exprs) exprs)
	catch-clauses (->> exprs reverse (take-while #(first= 'catch %)) reverse)
	exprs (drop-last (count catch-clauses) exprs)
	transformed-body
	`(run-pipeline nil
	   :error-handler
	   (fn [ex#]
	     (try
	       (let [result# (try
			       (throw ex#)
			       ~@catch-clauses)]
		 (complete result#))
	       (catch Exception e#
		 (redirect (pipeline (fn [_#] (throw e#))) nil))))
	   (fn [_#]
	     ~@exprs))]
    (if finally-clause
      (transform-finally transformed-body finally-clause)
      transformed-body)))

(defn async [body]
  `(do
     ~@(postwalk
	 (fn [expr]
	   (cond
	     (valid-expr? expr) (transform-expr expr)
	     (first= 'if expr) (transform-if expr)
	     (first= 'throw expr) (transform-throw expr)
	     (first= 'try expr) (transform-try expr)
	     :else expr))
	 body)))

;;;

(defn pfn [args]
  `(let [f# (fn ~@args)]
     (fn ~@(when (symbol? (first args)) (take 1 args))
       [~'& args#]
       (apply run-pipeline []
	 (concat
	   (map (fn [x#] (read-merge (constantly x#) conj)) args#)
	   [#(apply f# %)])))))

(defn future* [body]
  `(let [result# (result-channel)]
     (future
       (siphon-result
	 (run-pipeline nil
	   (fn [_#]
	     ~@body))
	 result#))
     result#))
