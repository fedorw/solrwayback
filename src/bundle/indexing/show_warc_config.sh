#!/bin/bash

pushd ${BASH_SOURCE%/*} > /dev/null

java -cp warc-indexer-3.2.0-KB-SNAPSHOT-jar-with-dependencies.jar uk.bl.wa.util.ConfigPrinter

