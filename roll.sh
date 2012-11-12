# install the roll plugin for every server
# https://github.com/karussell/elasticsearch-rollindex/
# call this script ala roll.sh <indexPrefix> <type> <numberOfRemainingIndices>

indexprefix=$1
type=$2
roll=$3
SRC=
host=localhost:9200
# grab the mapping for 'type' from somewhere
MAP_PATH=$SRC"path/to/$type.json"
mappings=$(cat $MAP_PATH)
echo ""
echo `date`

content='{
   "settings" : {
         "number_of_shards" : 3,
         "number_of_replicas": 1
    },
    "mappings" : '$mappings'
}'
echo $content
curl -XPUT "$host/_rollindex?indexPrefix=$indexprefix&searchIndices=$roll&rollIndices=$roll" -d "$content"





