#!/usr/bin/env bash

echo "Tesing elasticsearch queries"

echo "starting cluster"

DOCKER_CID=$(docker run -d -p 9200:9200 -p 9300:9300 -e "discovery.type=single-node" elasticsearch:7.4.0)

sleep 10

echo "intialising index mapping"

clj -A:dev -e "(do (require 'setup) (setup/init-cluster!))"

echo "running tests"

RESULT=$(clj -A:test -m openmind.elastic-test)

echo "killing cluster"

docker kill $DOCKER_CID

# Ghetto exit test. Fails if there are any failures, any errors, or any changes
# to the output format.
echo $RESULT | grep "Testing openmind.elastic-test" | grep ':fail 0' | grep ':error 0' > /dev/null

EXIT=$?

echo $RESULT

exit $EXIT