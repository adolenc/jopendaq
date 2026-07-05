;; Subscribe a Clojure function to the instance's core event stream: each
;; property change is reported through the handler until it is unsubscribed.

(import '(com.opendaq Instance Channel CoreEventArgs Daq EventCallback))

(def instance (Instance.))
(.addDevice instance "daqref://device0")

(def channel (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))

(def core-event (.getOnCoreEvent (.getContext instance)))
(def handler
  (.addHandler core-event
               (reify EventCallback
                 (handle [_ _sender event-args]
                   (let [event (.asType event-args CoreEventArgs)]
                     (println (str "  " (.getEventName event) ": "
                                   (Daq/unbox (.get (.getParameters event) "Name")))))))))

;; While subscribed, each property change is reported by the handler above.
(println "subscribed:")
(.setPropertyValue channel "Frequency" 25.0)
(.setPropertyValue channel "Amplitude" 7.5)

;; removeHandler unsubscribes; further changes fire nothing.
(.removeHandler core-event handler)
(println "unsubscribed (no lines expected below):")
(.setPropertyValue channel "Frequency" 50.0)
