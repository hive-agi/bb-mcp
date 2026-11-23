(ns bb-mcp.protocol-test
  "Content-Length counts BYTES (UTF-8), not characters."
  (:require [clojure.test :refer [deftest is testing]]
            [bb-mcp.protocol :as protocol]
            [bb-mcp.wire.utf8 :as utf8]))

(defn- framed
  "`body` with a Content-Length header of its UTF-8 byte count."
  [body]
  (str "Content-Length: " (utf8/byte-length body) "\r\n\r\n" body))

(deftest utf8-byte-length
  (is (= 1 (utf8/byte-length "a")))
  (is (= 2 (utf8/byte-length "é")))
  (is (= 3 (utf8/byte-length "€")))
  (is (= 4 (utf8/byte-length "😀")))
  (is (= 10 (utf8/byte-length "aé€😀"))))

(deftest a-multi-byte-body-with-content-length-parses
  (let [body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ação €😀\"}"]
    (testing "the body is read whole"
      (is (= "ação €😀"
             (:method (with-in-str (framed body) (protocol/read-message))))))
    (testing "and the next message is not eaten"
      (is (= [1 2]
             (with-in-str (str (framed body)
                               (framed "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"x\"}"))
               [(:id (protocol/read-message)) (:id (protocol/read-message))]))))
    (testing "an ASCII body reads as before"
      (is (= 3 (:id (with-in-str (framed "{\"id\":3}") (protocol/read-message))))))))
