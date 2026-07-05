;; Call a device's function properties.  getPropertyValue returns a FUNC
;; property as a FunctionObject: call it with natural Java arguments and the
;; result comes back unboxed.  Clojure passes varargs as an explicit array, so
;; the arguments go through object-array.

(import '(com.opendaq Instance Device FunctionObject))

(def instance (Instance.))
(def device (.addDevice instance "daqref://device0"))

(def sum (.getPropertyValue device "Protected.Sum"))
(println "Protected.Sum(7, 5)   =" (.call sum (object-array [7 5])))
(println "Protected.Sum(40, 2)  =" (.call sum (object-array [40 2])))
(println "Protected.Sum(100, 1) ="
         (.call (.getPropertyValue device "Protected.Sum") (object-array [100 1])))

(def sum-list (.getPropertyValue device "Protected.SumList"))
(println "Protected.SumList((1 2 3 4)) =" (.call sum-list (object-array [[1 2 3 4]])))
(println "Protected.SumList(()) =" (.call sum-list (object-array [[]])))

(try
  (.call sum (object-array [1 2 3]))
  (println "Unexpected: wrong arity was accepted.")
  (catch RuntimeException e
    (println)
    (println (str "Wrong arity is rejected: " (.getMessage e)))))
