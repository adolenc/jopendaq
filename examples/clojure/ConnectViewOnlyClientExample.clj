;; Connect to a networked openDAQ device as a view-only client (reads work,
;; configuration writes are refused).  Expects a device/simulator at
;; daq.nd://127.0.0.1 or at $OPENDAQ_DEVICE.

(import '(com.opendaq Instance PropertyObject Property CoreType Daq))

(defn first-writable-property-name
  "Name of the object's first visible, non-read-only, non-callable property, or
   nil.  Used to probe whether the connection actually refuses writes."
  [object]
  (some (fn [property]
          (let [type (.getValueType property)]
            (when (and (not (.getReadOnly property))
                       (not= type CoreType/FUNC)
                       (not= type CoreType/PROC))
              (.getName property))))
        (.getVisibleProperties object)))

(def instance (Instance.))

(def config (.createDefaultAddDeviceConfig instance))
(def general (.asType (.getPropertyValue config "General") PropertyObject))
(def client-type (.getProperty general "ClientType"))
(def options (Daq/unbox (.getSelectionValues client-type)))

(println "Available ClientType options:")
(cond
  (instance? java.util.Map options)
  (doseq [e (sort-by #(long (key %)) (seq options))]
    (println (str "  " (long (key e)) " = " (val e))))
  (instance? java.util.List options)
  (dotimes [i (count options)]
    (println (str "  " i " = " (nth options i)))))

(.setPropertyValue config "General.ClientType" 2)   ; 2 = view-only
(def connection-string (or (System/getenv "OPENDAQ_DEVICE") "daq.nd://127.0.0.1"))

(try
  (let [device (.addDevice instance connection-string config)]
    (println (str "Connected to " (.getName device) " as a view-only client."))
    (println (str "Visible signals: " (.size (.getSignalsRecursive device))))
    (let [property-name (first-writable-property-name device)]
      (if (nil? property-name)
        (println "Device exposes no writable property to probe.")
        (try
          (.setPropertyValue device property-name (.getPropertyValue device property-name))
          (println (str "Unexpected: writing \"" property-name "\" was allowed."))
          (catch RuntimeException e
            (println (str "Write to \"" property-name "\" refused, as expected for view-only: "
                          (.getMessage e))))))))
  (catch RuntimeException e
    (println (str "Could not connect to " connection-string ": " (.getMessage e)))
    (println "Start an openDAQ device/simulator there, or set OPENDAQ_DEVICE.")))
