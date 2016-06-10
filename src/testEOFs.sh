#!/usr/bin/env bash
# Computes the 3D EOFs using CSFR dataset
# You need to change the memory setting and location of the data for different platforms

DIR="$(cd "`dirname "$0"`"/..; pwd)"
LOGDIR=$DIR/eventLogs
JARNAME=$1
PLATFORM=CORI
INPUTSPEC=$DIR/input.spec

JOBNAME=$INPUTSPEC
LOGNAME="$JOBNAME.log"

#[ -e $OUTDEST ] && (echo "Job already run successfully, stopping"; exit 1)

if [ $PLATFORM == "EC2" ]; then
  # On EC2 there are 32 cores/node and 244GB/node 
  # use 30 executors to use 960 cores
  # use as much memory as available so can cache the entire 2GB RDD
NUMEXECUTORS=30
NUMCORES=32
DRIVERMEMORY="210G"
EXECUTORMEMORY="210G"
MASTER="spark://ec2-54-187-175-26.us-west-2.compute.amazonaws.com:7077"
elif [ $PLATFORM == "CORI" ]; then 
  # On Cori there are 32 cores/node and 128GB/node
  # To have a fair comparison to EC2, use more physical nodes but less cores per node so that each core has the same amount of memory as on EC2:
  # on EC2, 32 cores have 210G, so configure Cori so 16 cores have 105G, then on EC2 have 30 nodes, so need 60 on Cori
#NUMEXECUTORS=60
#NUMCORES=16
NUMEXECUTORS=12
NUMCORES=32
DRIVERMEMORY=100G
EXECUTORMEMORY=100G
MASTER=$SPARKURL
fi

#  --conf spark.task.maxFailures=4 \
#  --conf spark.worker.timeout=1200000 \
#  --conf spark.network.timeout=1200000 \
spark-submit --verbose \
  --master $MASTER \
  --num-executors $NUMEXECUTORS \
  --executor-cores $NUMCORES \
  --driver-memory $DRIVERMEMORY \
  --executor-memory $EXECUTORMEMORY \
  --conf spark.eventLog.enabled=true \
  --conf spark.eventLog.dir=$LOGDIR \
  --conf spark.driver.maxResultSize=30G \
  --jars $JARNAME \
  --class org.apache.spark.mllib.climate.computeEOFs \
  $JARNAME $INPUTSPEC 2>&1 | tee $LOGNAME

