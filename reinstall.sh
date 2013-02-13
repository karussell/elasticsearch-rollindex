ES=/usr/share/elasticsearch
sudo $ES/bin/plugin remove rollindex
mvn -DskipTests clean package
FILE=`ls ./target/elasticsearch-*zip`
sudo $ES/bin/plugin -url file:$FILE -install rollindex
sudo service elasticsearch restart