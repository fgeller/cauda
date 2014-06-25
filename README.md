# cauda

REST application to manage a social queue, mostly an experiment to play with Clojure though.


## Usage

POST /users {"nick": "Hans"}         =>
GET  /users                          => {"1": {"nick": "Hans"}}
POST /users/1/queue {"data": "acme"} =>
GET  /users/1/queue                  => ["acme"]
POST /users/1/veto {"data": "acme"}  =>
GET  /vetos                          => ["acme"]
