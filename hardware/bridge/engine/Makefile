# Raspberry Pi(aarch64)上でビルドする。先に libraop を
#   CFLAGS="-DRAOP_LATENCY_MIN=4410" make CC=gcc HOST=linux PLATFORM=aarch64
# でビルドしておくこと(パッチ適用済みのもの)。
LR ?= $(HOME)/airplay-bridge/libraop
OBJ = $(LR)/bin/linux/aarch64
CFLAGS = -O2 -Wall -DRAOP_LATENCY_MIN=4410 -D_GNU_SOURCE \
	-I$(LR)/src -I$(LR)/crosstools/src -I$(LR)/libcodecs/targets/include/addons
LIBS = $(OBJ)/cross_log.o $(OBJ)/cross_ssl.o $(OBJ)/cross_util.o $(OBJ)/cross_net.o $(OBJ)/platform.o \
	$(LR)/lib/linux/aarch64/libraop.a \
	$(LR)/libcodecs/targets/linux/aarch64/libcodecs.a $(LR)/libmdns/targets/linux/aarch64/libmdns.a \
	-lasound -lsamplerate -lpthread -ldl -lm -lstdc++ -latomic

airplay-bridge-engine: airplay_bridge_engine.c
	$(CC) $(CFLAGS) $< $(LIBS) -o $@

clean:
	rm -f airplay-bridge-engine
