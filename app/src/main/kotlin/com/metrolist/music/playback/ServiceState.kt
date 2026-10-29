/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

@Volatile
var isMusicServiceRunning = false

const val CHANNEL_ID = "music_channel_01"
const val NOTIFICATION_ID = 888
const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
