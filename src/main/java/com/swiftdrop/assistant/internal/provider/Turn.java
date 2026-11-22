package com.swiftdrop.assistant.internal.provider;

/** One message of the conversation, oldest first; the last one is the user's new message. */
public record Turn(boolean fromUser, String text) {
}
