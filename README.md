# cauda

REST application to manage a social queue, mostly an experiment to play with Clojure though.


## Usage

    POST /users {"nick": "Hans"}         =>
    GET  /users                          => {"1": {"nick": "Hans"}}
    POST /users/1/queue {"data": "acme"} =>
    GET  /users/1/queue                  => ["acme"]
    POST /users/1/veto {"data": "acme"}  =>
    GET  /vetos                          => ["acme"]

## Deploy

Make sure you have [leiningen](http://leiningen.org/) installed and then issue:

    lein uberjar
    
Make sure you have the schema configuration in `schema.edn` and then start via

    java -jar $PWD/target/cauda-0.1.0-SNAPSHOT-standalone.jar
    
or an alternative.
