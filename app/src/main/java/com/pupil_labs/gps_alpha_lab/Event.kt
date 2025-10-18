package com.janatalab.multi_tracker

data class Event(private var name: String = "gps_event") {
    fun toJson() = """{"name": "${name}"}"""
    fun setName(newName: String) {
        name = newName
    }
}