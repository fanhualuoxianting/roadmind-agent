package com.roadmind.server.preference;

public class PreferenceNotFoundException extends RuntimeException {

    public PreferenceNotFoundException(String category, String preferenceKey) {
        super("偏好不存在: " + category + "/" + preferenceKey);
    }
}
