(ns rethinkdb.core-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [rethinkdb.core :refer :all]
            [rethinkdb.query :as r]))

(def test-db "test_cljrethinkdb")

(defn clear-db [test-fn]
  (let [conn (connect)
        db-list (-> (r/db-list) (r/run conn))]
    (if (some #{(name test-db)} db-list)
      (-> (r/db-drop test-db) (r/run conn)))
    (r/run (r/db-create test-db) conn)
    (close conn))
  (test-fn))

(defmacro with-test-db [& body]
  (cons 'do (for [term body]
              `(-> (r/db test-db)
                   ~term
                   (r/run ~'conn)))))

(deftest core-test
  (let [conn (connect)]
    (testing "table management"
      (with-test-db
        (r/table-create :playground {:durability "soft"})
        (r/table-create :pokedex {:primary-key :national_no})
        (-> (r/table :pokedex)
            (r/index-create :type (r/fn [row]
                                     (r/get-field row :type))))
        (-> (r/table :pokedex)
            (r/index-create :moves (r/fn [row]
                                     (r/get-field row :moves))
                            {:multi true}))
        (r/table-create :temp)
        (-> (r/table :pokedex)
            (r/index-rename :moves :move))
        (r/table-drop :temp))
      (is (= ["playground" "pokedex"] (with-test-db (r/table-list)))))
    (testing "writing data"
      (with-test-db
        (-> (r/table :pokedex)
            (r/insert [{:national_no 25
                        :name "Pikachu"
                        :type "Electric"
                        :last_seen (t/date-time 2014 10 20)
                        :moves ["Tail Whip" "Tail Whip" "Growl"]}
                       {:national_no 81
                        :name "Magnemite"
                        :type "Electric"}]))))
    (testing "selecting data"
      (let [pikachu-with-pk (with-test-db (-> (r/table :pokedex) (r/get 25)))
            pikachu-with-index (first (with-test-db (-> (r/table :pokedex)
                                                        (r/get-all ["Electric"] {:index :type})
                                                        (r/filter (r/fn [row]
                                                                    (r/eq "Pikachu" (r/get-field row :name)))))))]
        (is (= pikachu-with-pk pikachu-with-index))
        (is (not-empty (with-test-db
                         (-> (r/table :pokedex)
                             (r/get-all ["Tail Whip"] {:index :move})))))))
    (testing "cursors"
      (with-test-db
        (-> (r/table :playground)
            (r/insert (take 100000 (repeat {})))))
      (take 100 (with-test-db
                  (-> (r/table :playground)
                      (r/skip 100)))))
    (testing "transformations"
      (is (= [{:type "Electric"} {:type "Electric"}]
             (with-test-db (-> (r/table :pokedex)
                               (r/get-all ["Electric"] {:index :type})
                               (r/with-fields [:type])))))
      (is (= (with-test-db
               (-> (r/table :pokedex)
                   (r/order-by (r/desc :name))))
             (reverse (with-test-db
                        (-> (r/table :pokedex)
                            (r/order-by (r/asc :name))))))))
    (testing "manipulating documents"
      (with-test-db
        (-> (r/table :pokedex)
            (r/get 25)
            (r/update
              (r/fn [row]
                {:moves (r/set-insert (r/get-field row :moves) "Thunder Shock")}))))
      (is (= ["Tail Whip" "Growl" "Thunder Shock"]
             (with-test-db
               (-> (r/table :pokedex)
                   (r/get 25)
                   (r/get-field :moves)))))
      (is (= ["Tail Whip"]
             (with-test-db
               (-> (r/table :pokedex)
                   (r/get 25)
                   (r/get-field :moves)
                   (r/set-difference ["Growl" "Thunder Shock"]))))))
    (close conn)))

(use-fixtures :once clear-db)
