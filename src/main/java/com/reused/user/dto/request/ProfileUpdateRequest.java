package com.reused.user.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Tracks JSON presence because an absent image keeps it, but explicit null removes it. */
public class ProfileUpdateRequest {
    @Size(min = 2, max = 20)
    private String nickname;
    @Size(max = 200)
    private String bio;
    @Positive
    private Long imageId;
    private boolean bioPresent;
    private boolean imagePresent;

    public String getNickname() { return nickname; }
    public String getBio() { return bio; }
    public Long getImageId() { return imageId; }
    public boolean hasBio() { return bioPresent; }
    public boolean hasImage() { return imagePresent; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setBio(String bio) { this.bio = bio; this.bioPresent = true; }
    public void setImageId(Long imageId) { this.imageId = imageId; this.imagePresent = true; }
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unsupported profile field"); }
}
