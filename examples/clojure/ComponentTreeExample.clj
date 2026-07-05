;; Walk the whole component tree of the reference device and print it as a
;; text diagram, listing each component's visible properties with their
;; current values.

(import '(com.opendaq Instance CoreType Daq PropertyObject Folder))
(require '[clojure.string :as str])

(def scalar-types #{CoreType/BOOL CoreType/INT CoreType/FLOAT CoreType/STRING
                    CoreType/RATIO CoreType/COMPLEX_NUMBER})

(defn type-label
  "Readable type name for a component, e.g. \"Channel\" or \"FunctionBlock\"."
  [component]
  (if-let [t (Daq/componentType component)]
    (.getSimpleName t)
    "Component"))

(defn type-name
  "Readable name for a property's core type, e.g. INT -> \"Int\"."
  [core-type]
  (->> (str/split (str/lower-case (.name core-type)) #"_")
       (map str/capitalize)
       (str/join "")))

(defn property-value-string
  "Printable value of a property.  getPropertyValue already returns scalars as
   their natural Java values; structured ones are shown as \"<Type>\" instead."
  [object property]
  (if (contains? scalar-types (.getValueType property))
    (let [value (.getPropertyValue object (.getName property))]
      (if (instance? String value) (str "\"" value "\"") (str value)))
    (str "<" (type-name (.getValueType property)) ">")))

(defn draw-properties [component prefix]
  (when (.isA component PropertyObject)
    (let [object (.asType component PropertyObject)]
      (doseq [property (.getVisibleProperties object)]
        (println (str prefix "• " (.getName property)
                      " : " (type-name (.getValueType property))
                      " = " (property-value-string object property)))))))

(defn children
  "The immediate child components of a component if it is a folder."
  [component]
  (if (.isA component Folder)
    (.getItems (.asType component Folder) nil)
    []))

(defn draw-children [component prefix]
  (let [kids (children component)
        n (count kids)]
    (dotimes [i n]
      (let [child (nth kids i)
            last? (= i (dec n))
            child-prefix (str prefix (if last? "   " "│  "))]
        (println (str prefix (if last? "└─ " "├─ ") (.getName child)
                      " : " (type-label child) " (" (.getLocalId child) ")"))
        (draw-properties child child-prefix)
        (draw-children child child-prefix)))))

(def instance (Instance.))
(.addDevice instance "daqref://device0")

(def root (.getRootDevice instance))
(println (str (.getName root) " : " (type-label root) " (" (.getLocalId root) ")"))
(draw-properties root "")
(draw-children root "")
