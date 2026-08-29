package com.example.chat

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan("com.example.chat")
class ChatApplication

fun main(args: Array<String>) {
	runApplication<ChatApplication>(*args)
}
