cmake_minimum_required(VERSION 3.4.1)

find_library( log-lib log )

#######################################################
# ogg
file( GLOB ogg_src_files
    "src/main/jni/ogg/*.c"
)

add_library( ogg SHARED ${ogg_src_files} )

target_include_directories(ogg SYSTEM PUBLIC src/main/jni)

target_link_libraries( ogg )


#######################################################
# vorbis
file( GLOB vorbis_src_files
    "src/main/jni/vorbis/*.c"
    "src/main/jni/libvorbis-jni/*.c"
)
add_library( vorbis SHARED ${vorbis_src_files} )
target_link_libraries( vorbis ogg  ${log-lib})


#######################################################
# FLAC
file( GLOB flac_src_files
    "src/main/jni/FLAC/FLAC/*.c"
)
add_library( flac SHARED ${flac_src_files} )

target_include_directories(flac PRIVATE src/main/jni/FLAC)

target_link_libraries( flac )


#######################################################
# player
file( GLOB player_src_files
    "src/main/jni/*.c"
    "src/main/jni/*.cpp"
    "src/main/jni/fftpack/*.cpp"
    "src/main/jni/filters/*.cpp"
    "src/main/jni/sonic/*.c"
    "src/main/jni/mpg123/*.c"
)

add_library( jni-mpg123-sonic SHARED ${player_src_files} )

target_link_libraries( jni-mpg123-sonic ${log-lib} vorbis ogg flac)