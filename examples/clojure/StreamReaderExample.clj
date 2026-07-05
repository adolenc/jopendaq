;; The minimal openDAQ flow: connect to the reference (simulator) device, take
;; its first analog channel's signal, and stream samples off it.

(import '(com.opendaq Instance Channel StreamReader))

(def instance (Instance.))
(.addDevice instance "daqref://device0")

(def channel (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))
(def signal (.get (.getSignals channel) 0))
(def reader (StreamReader. signal))

(println "some samples:" (java.util.Arrays/toString (.read reader 100 1000)))
(println "and more samples:" (java.util.Arrays/toString (.read reader 100 1000)))
(println "and more still:" (java.util.Arrays/toString (.read reader 100 1000)))
