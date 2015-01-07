#!/bin/bash

# Configuring Running Directory
TOP_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $TOP_DIR
echo -e "\nThe JXIO top directory is $TOP_DIR\n"

TARGET=jxio.jar
BIN_FOLDER=$TOP_DIR/bin
LIB_FOLDER=$TOP_DIR/src/lib
SRC_JAVA_FOLDER=$TOP_DIR/src/java
SRC_JAVA_FILES="$SRC_JAVA_FOLDER/org/accelio/jxio/*.java $SRC_JAVA_FOLDER/org/accelio/jxio/exceptions/*.java $SRC_JAVA_FOLDER/org/accelio/jxio/impl/*.java $SRC_JAVA_FOLDER/org/accelio/jxio/jxioConnection/*.java $SRC_JAVA_FOLDER/org/accelio/jxio/jxioConnection/impl/*.java $SRC_JAVA_FOLDER/org/apache/lucene/facet/taxonomy/LRUHashMap.java"
NATIVE_LIBS="libjxio.so libxio.so"
if [ -z "$DONT_STRIP" ]; then
	STRIP_COMMAND="strip -s"
else
	STRIP_COMMAND="touch" #do not strip libraries from symbols
fi

# Clean
rm -fr $BIN_FOLDER
mkdir -p $BIN_FOLDER

## Prepare VERSION files
GIT_VERSION=`git describe --long --tags --always --dirty`
GIT_VERSION_XIO=`cd src/accelio; git describe --long --tags --always --dirty`
echo "JXIO git version is: $GIT_VERSION"
echo "AccelIO git version is: $GIT_VERSION_XIO"
echo "$GIT_VERSION" > version

# Prepare jar MANIFEST file
cp manifest.template manifest.txt
sed -i "s/Implementation-Version: .*/Implementation-Version: $GIT_VERSION/" manifest.txt
echo "Implementation-Version-AccelIO: $GIT_VERSION_XIO" >> manifest.txt

## Build Accelio
echo "Build Accelio... libxio C code"
cd $TOP_DIR
git submodule update --init
cd src/accelio/ && make distclean -si > /dev/null 2>&1;
./autogen.sh && ./configure --silent --disable-raio-build --enable-silent-rules && make -s && cp -f src/usr/.libs/libxio.so $BIN_FOLDER  && $STRIP_COMMAND $BIN_FOLDER/libxio.so
if [[ $? != 0 ]] ; then
    echo "FAILURE! stopped JXIO build"
    exit 1
fi

## Build JXIO
# Build JXIO C code
echo "Build JXIO C code"
cd $TOP_DIR
cd src/c/ && ./autogen.sh && ./configure --silent && make clean -s
status=$?
make -s
if [[ $? != 0 ]] || [[ $status != 0 ]]; then
    echo "FAILURE! stopped JXIO build"
    exit 1
fi
cp -f src/.libs/libjxio.so $BIN_FOLDER && $STRIP_COMMAND $BIN_FOLDER/libjxio.so
# Build JXIO JAVA code
echo "Build JXIO Java code"
cd $TOP_DIR
javac -cp $LIB_FOLDER/commons-logging.jar -d $BIN_FOLDER $SRC_JAVA_FILES
if [[ $? != 0 ]] ; then
    echo "FAILURE! stopped JXIO build"
    exit 1
fi
# Create JXIO Java docs
echo "Creating JXIO Java docs"
javadoc -quiet -classpath $LIB_FOLDER/commons-logging.jar -d $TOP_DIR/docs -sourcepath src/java/ org.accelio.jxio
if [[ $? != 0 ]] ; then
    echo "FAILURE! stopped JXIO build"
    exit 1
fi
# Create JXIO Jar
echo "Creating JXIO jar..."
cd $BIN_FOLDER && jar -cfm $TARGET ../manifest.txt org $NATIVE_LIBS
if [[ $? != 0 ]] ; then
    echo "FAILURE! stopped JXIO build"
    exit 1
fi

echo -e "\nJXIO Build completed SUCCESSFULLY!\n"
