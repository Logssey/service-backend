package com.reused.image.service;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** Spring's Jackson handler instantiator injects the resolver for every annotated response field. */
public class ProfileImageUrlSerializer extends ValueSerializer<String> {
    private final ProfileImageUrlResolver resolver;
    public ProfileImageUrlSerializer(ProfileImageUrlResolver resolver) { this.resolver = resolver; }

    @Override
    public void serialize(String value, JsonGenerator generator, SerializationContext context) {
        String url = resolver.resolve(value);
        if (url == null) generator.writeNull();
        else generator.writeString(url);
    }
}
