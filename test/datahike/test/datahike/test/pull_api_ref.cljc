(ns datahike.test.pull-api-ref
  (:require
    #?(:cljs [cljs.test :as t :refer-macros [is deftest testing]]
       :clj  [clojure.test :as t :refer [is deftest testing]])
    [datahike.api :as da]
    [datahike.core :as d]
    ))



(def test-schema
  [{:db/ident       :aka
    :db/cardinality :db.cardinality/many
    :db/valueType   :db.type/string}
   {:db/ident       :name
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}
   {:db/ident       :child
    :db/cardinality :db.cardinality/many
    :db/valueType   :db.type/ref}
   {:db/ident       :friend
    :db/cardinality :db.cardinality/many
    :db/valueType   :db.type/ref}
   {:db/ident       :enemy
    :db/cardinality :db.cardinality/many
    :db/valueType   :db.type/ref}
   {:db/ident       :father
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/ref}
   {:db/ident       :part
    :db/valueType   :db.type/ref
     :db/isComponent true
    :db/cardinality :db.cardinality/many}
   {:db/ident       :spec
    :db/valueType   :db.type/ref
    :db/isComponent true
    :db/cardinality :db.cardinality/one}])


(def test-datoms
  (->>
   [[1 :name  "Petr"]
    [1 :aka   "Devil"]
    [1 :aka   "Tupen"]
    [2 :name  "David"]
    [3 :name  "Thomas"]
    [4 :name  "Lucy"]
    [5 :name  "Elizabeth"]
    [6 :name  "Matthew"]
    [7 :name  "Eunan"]
    [8 :name  "Kerri"]
    [9 :name  "Rebecca"]
    [1 :child 2]
    [1 :child 3]
    [2 :father 1]
    [3 :father 1]
    [6 :father 3]
    [10 :name "Part A"]
    [11 :name "Part A.A"]
    [10 :part 11]
    [12 :name "Part A.A.A"]
    [11 :part 12]
    [13 :name "Part A.A.A.A"]
    [12 :part 13]
    [14 :name "Part A.A.A.B"]
    [12 :part 14]
    [15 :name "Part A.B"]
    [10 :part 15]
    [16 :name "Part A.B.A"]
    [15 :part 16]
    [17 :name "Part A.B.A.A"]
    [16 :part 17]
    [18 :name "Part A.B.A.B"]
    [16 :part 18]]
   (mapv (fn [[e a v]] [:db/add e a v]))))

(def config {:attribute-refs? true})

(def test-conn
  (do (da/delete-database config)
      (da/create-database config)
      (let [conn (da/connect config)
            _ (da/transact conn test-schema)
            irmap (:ident-ref-map @conn)
            max-eid (:max-eid @conn)
            _ (println "meid" max-eid)
            db-datoms (mapv (fn [[op e a v]]
                              [op (+ max-eid e) (get irmap a) (if (number? v) (+ max-eid v) v)])
                            test-datoms)
            _ (println db-datoms)]
        (da/transact conn db-datoms)
        {:db @conn :e0 max-eid})))

(def test-db (:db test-conn))
(def test-e0 (:e0 test-conn))

(deftest test-pull-attr-spec
  (is (= {:name "Petr" :aka ["Devil" "Tupen"]}
         (d/pull test-db '[:name :aka] (+ test-e0 1))))

  (is (= {:name "Matthew" :father {:db/id (+ test-e0 3)} :db/id (+ test-e0 6)}
         (d/pull test-db '[:name :father :db/id] (+ test-e0 6))))

  (is (= [{:name "Petr"} {:name "Elizabeth"}
          {:name "Eunan"} {:name "Rebecca"}]
         (d/pull-many test-db '[:name]
                      (mapv (partial + test-e0) [1 5 7 9])))))

(deftest test-pull-reverse-attr-spec
  (is (= {:name "David" :_child [{:db/id (+ test-e0 1)}]}
         (d/pull test-db '[:name :_child] (+ test-e0 2))))

  (is (= {:name "David" :_child [{:name "Petr"}]}
         (d/pull test-db '[:name {:_child [:name]}] (+ test-e0 2))))

  (testing "Reverse non-component references yield collections"
    (is (= {:name "Thomas" :_father [{:db/id (+ test-e0 6)}]}
           (d/pull test-db '[:name :_father] (+ test-e0 3))))

    (is (= {:name "Petr" :_father [{:db/id (+ test-e0 2)} {:db/id (+ test-e0 3)}]}
           (d/pull test-db '[:name :_father] (+ test-e0 1))))

    (is (= {:name "Thomas" :_father [{:name "Matthew"}]}
           (d/pull test-db '[:name {:_father [:name]}] (+ test-e0 3))))

    (is (= {:name "Petr" :_father [{:name "David"} {:name "Thomas"}]}
           (d/pull test-db '[:name {:_father [:name]}] (+ test-e0 1))))))

(deftest test-pull-component-attr
  (let [parts {:name "Part A",
               :part
               [{:db/id (+ test-e0 11)
                 :name "Part A.A",
                 :part
                 [{:db/id (+ test-e0 12)
                   :name "Part A.A.A",
                   :part
                   [{:db/id (+ test-e0 13) :name "Part A.A.A.A"}
                    {:db/id (+ test-e0 14) :name "Part A.A.A.B"}]}]}
                {:db/id (+ test-e0 15)
                 :name "Part A.B",
                 :part
                 [{:db/id (+ test-e0 16)
                   :name "Part A.B.A",
                   :part
                   [{:db/id (+ test-e0 17) :name "Part A.B.A.A"}
                    {:db/id (+ test-e0 18) :name "Part A.B.A.B"}]}]}]}
        rpart (update-in parts [:part 0 :part 0 :part]
                         (partial into [{:db/id (+ test-e0 10)}]))
        irmap (get test-db :ident-ref-map)
        recdb (da/db-with test-db [(d/datom (+ test-e0 12) (:part irmap) (+ test-e0 10))])]

    (testing "Component entities are expanded recursively"
      (is (= parts (d/pull test-db '[:name :part] (+ test-e0 10)))))

    (testing "Reverse component references yield a single result"
      (is (= {:name "Part A.A" :_part {:db/id (+ test-e0 10)}}
             (d/pull test-db [:name :_part] (+ test-e0 11))))

      (is (= {:name "Part A.A" :_part {:name "Part A"}}
             (d/pull test-db [:name {:_part [:name]}] (+ test-e0 11)))))

    (testing "Like explicit recursion, expansion will not allow loops"
      (is (= rpart (d/pull recdb '[:name :part] (+ test-e0 10)))))))

(deftest test-pull-wildcard
  (is (= {:db/id (+ test-e0 1) :name "Petr" :aka ["Devil" "Tupen"]
          :child [{:db/id (+ test-e0 2)} {:db/id (+ test-e0 3)}]}
         (d/pull test-db '[*] (+ test-e0 1))))

  (is (= {:db/id (+ test-e0 2) :name "David" :_child [{:db/id (+ test-e0 1)}] :father {:db/id (+ test-e0 1)}}
         (d/pull test-db '[* :_child] (+ test-e0 2)))))

(deftest test-pull-limit
  (let [irmap (get test-db :ident-ref-map)
        db (da/db-with test-db
            (concat
             [(d/datom (+ test-e0 4) (:friend irmap) (+ test-e0 5))
              (d/datom (+ test-e0 4) (:friend irmap)  (+ test-e0 6))
              (d/datom (+ test-e0 4) (:friend irmap)  (+ test-e0 7))
              (d/datom (+ test-e0 4) (:friend irmap)  (+ test-e0 8))]
             (for [idx (range 2000)]
               (d/datom (+ test-e0 8) (:aka irmap) (str "aka-" idx)))))]

    (testing "Without an explicit limit, the default is 1000"
      (is (= 1000 (->> (d/pull db '[:aka] (+ test-e0 8)) :aka count))))

    (testing "Explicit limit can reduce the default"
      (is (= 500 (->> (d/pull db '[(limit :aka 500)] (+ test-e0 8)) :aka count)))
      (is (= 500 (->> (d/pull db '[[:aka :limit 500]] (+ test-e0 8)) :aka count))))

    (testing "Explicit limit can increase the default"
      (is (= 1500 (->> (d/pull db '[(limit :aka 1500)] (+ test-e0 8)) :aka count))))

    (testing "A nil limit produces unlimited results"
      (is (= 2000 (->> (d/pull db '[(limit :aka nil)] (+ test-e0 8)) :aka count))))

    (testing "Limits can be used as map specification keys"
      (is (= {:name "Lucy"
              :friend [{:name "Elizabeth"} {:name "Matthew"}]}
             (d/pull db '[:name {(limit :friend 2) [:name]}] (+ test-e0 4)))))))

(deftest test-pull-default
  (testing "Empty results return nil"
    (is (nil? (d/pull test-db '[:foo] (+ test-e0 1)))))

  (testing "A default can be used to replace nil results"
    (is (= {:foo "bar"}
           (d/pull test-db '[(default :foo "bar")] (+ test-e0 1))))
    (is (= {:foo "bar"}
           (d/pull test-db '[[:foo :default "bar"]] (+ test-e0 1))))))

(deftest test-pull-as
  (is (= {"Name" "Petr", :alias ["Devil" "Tupen"]}
         (d/pull test-db '[[:name :as "Name"] [:aka :as :alias]] (+ test-e0 1)))))

(deftest test-pull-attr-with-opts
  (is (= {"Name" "Nothing"}
         (d/pull test-db '[[:x :as "Name" :default "Nothing"]] (+ test-e0 1)))))

(deftest test-pull-map
  (testing "Single attrs yield a map"
    (is (= {:name "Matthew" :father {:name "Thomas"}}
           (d/pull test-db '[:name {:father [:name]}] (+ test-e0 6)))))

  (testing "Multi attrs yield a collection of maps"
    (is (= {:name "Petr" :child [{:name "David"}
                                 {:name "Thomas"}]}
           (d/pull test-db '[:name {:child [:name]}] (+ test-e0 1)))))

  (testing "Missing attrs are dropped"
    (is (= {:name "Petr"}
           (d/pull test-db '[:name {:father [:name]}] (+ test-e0 1)))))

  (testing "Non matching results are removed from collections"
    (is (= {:name "Petr" :child []}
           (d/pull test-db '[:name {:child [:foo]}] (+ test-e0 1)))))

  (testing "Map specs can override component expansion"
    (let [parts {:name "Part A" :part [{:name "Part A.A"} {:name "Part A.B"}]}]
      (is (= parts
             (d/pull test-db '[:name {:part [:name]}] (+ test-e0 10))))

      (is (= parts
             (d/pull test-db '[:name {:part 1}] (+ test-e0 10)))))))

(deftest test-pull-recursion
  (let [irmap (get test-db :ident-ref-map)
        db      (d/db-with test-db
                           [[:db/add (+ test-e0 4) (:friend irmap) (+ test-e0 5)]
                            [:db/add (+ test-e0 5) (:friend irmap) (+ test-e0 6)]
                            [:db/add (+ test-e0 6) (:friend irmap) (+ test-e0 7)]
                            [:db/add (+ test-e0 7) (:friend irmap) (+ test-e0 8)]
                            [:db/add (+ test-e0 4) (:enemy irmap) (+ test-e0 6)]
                            [:db/add (+ test-e0 5) (:enemy irmap) (+ test-e0 7)]
                            [:db/add (+ test-e0 6) (:enemy irmap) (+ test-e0 8)]
                            [:db/add (+ test-e0 7) (:enemy irmap) (+ test-e0 4)]])
        friends {:db/id (+ test-e0 4)
                 :name "Lucy"
                 :friend
                 [{:db/id (+ test-e0 5)
                   :name "Elizabeth"
                   :friend
                   [{:db/id (+ test-e0 6)
                     :name "Matthew"
                     :friend
                     [{:db/id (+ test-e0 7)
                       :name "Eunan"
                       :friend
                       [{:db/id (+ test-e0 8)
                         :name "Kerri"}]}]}]}]}
        enemies {:db/id (+ test-e0 4) :name "Lucy"
                 :friend
                 [{:db/id (+ test-e0 5) :name "Elizabeth"
                   :friend
                   [{:db/id (+ test-e0 6) :name "Matthew"
                     :enemy [{:db/id (+ test-e0 8) :name "Kerri"}]}]
                   :enemy
                   [{:db/id (+ test-e0 7) :name "Eunan"
                     :friend
                     [{:db/id (+ test-e0 8) :name "Kerri"}]
                     :enemy
                     [{:db/id (+ test-e0 4) :name "Lucy"
                       :friend [{:db/id (+ test-e0 5)}]}]}]}]
                 :enemy
                 [{:db/id (+ test-e0 6) :name "Matthew"
                   :friend
                   [{:db/id (+ test-e0 7) :name "Eunan"
                     :friend
                     [{:db/id (+ test-e0 8) :name "Kerri"}]
                     :enemy [{:db/id (+ test-e0 4) :name "Lucy"
                              :friend [{:db/id (+ test-e0 5) :name "Elizabeth"}]}]}]
                   :enemy
                   [{:db/id (+ test-e0 8) :name "Kerri"}]}]}]

    (testing "Infinite recursion"
      (is (= friends (d/pull db '[:db/id :name {:friend ...}] (+ test-e0 4)))))

    (testing "Multiple recursion specs in one pattern"
      (is (= enemies (d/pull db '[:db/id :name {:friend 2 :enemy 2}] (+ test-e0 4)))))

    (let [db (d/db-with db [[:db/add (+ test-e0 8) (:friend irmap) (+ test-e0 4)]])]
      (testing "Cycles are handled by returning only the :db/id of entities which have been seen before"
        (is (= (update-in friends (take 8 (cycle [:friend 0]))
                          assoc :friend [{:db/id (+ test-e0 4) :name "Lucy" :friend [{:db/id (+ test-e0 5)}]}])
               (d/pull db '[:db/id :name {:friend ...}] (+ test-e0 4))))))))

(deftest test-dual-recursion
  (let [_ (da/delete-database config)
        _ (da/create-database config)
        conn (da/connect config)
        schema [{:db/ident :part
                 :db/cardinality :db.cardinality/one
                 :db/valueType :db.type/ref}
                {:db/ident :spec
                 :db/cardinality :db.cardinality/one
                 :db/valueType :db.type/ref}]
        _ (da/transact conn schema)
        irmap (get @conn :ident-ref-map)
        test-e0 (:max-eid @conn)
        db (d/db-with @conn [[:db/add (+ test-e0 1) (:part irmap) (+ test-e0 2)]
                             [:db/add (+ test-e0 2) (:part irmap) (+ test-e0 3)]
                             [:db/add (+ test-e0 3) (:part irmap) (+ test-e0 1)]
                             [:db/add (+ test-e0 1) (:spec irmap) (+ test-e0 2)]
                             [:db/add (+ test-e0 2) (:spec irmap) (+ test-e0 1)]])]
    (is (= (d/pull db '[:db/id {:part ...} {:spec ...}] (+ test-e0 1))
           {:db/id (+ test-e0 1),
            :spec {:db/id (+ test-e0 2)
                   :spec {:db/id (+ test-e0 1),
                          :spec {:db/id (+ test-e0 2)}, :part {:db/id (+ test-e0 2)}}
                   :part {:db/id (+ test-e0 3),
                          :part {:db/id (+ test-e0 1),
                                 :spec {:db/id (+ test-e0 2)},
                                 :part {:db/id (+ test-e0 2)}}}}
            :part {:db/id (+ test-e0 2)
                   :spec {:db/id (+ test-e0 1), :spec {:db/id (+ test-e0 2)}, :part {:db/id (+ test-e0 2)}}
                   :part {:db/id (+ test-e0 3),
                          :part {:db/id (+ test-e0 1),
                                 :spec {:db/id (+ test-e0 2)},
                                 :part {:db/id (+ test-e0 2)}}}}}))))

(deftest test-deep-recursion
  (let [start 100
        depth 1500
        irmap (get test-db :ident-ref-map)
        txd   (mapcat
               (fn [idx]
                 [(d/datom idx (:name irmap) (str "Person-" idx))
                  (d/datom (dec idx) (:friend irmap) idx)])
               (range (inc start) depth))
        db    (da/db-with test-db (concat txd [(d/datom start (:name irmap) (str "Person-" start))]))
        pulled (d/pull db '[:name {:friend ...}] start)
        path   (->> [:friend 0]
                    (repeat (dec (- depth start)))
                    (into [] cat))]
    (is (= (str "Person-" (dec depth))
           (:name (get-in pulled path))))))
