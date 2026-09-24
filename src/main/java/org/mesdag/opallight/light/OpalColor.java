package org.mesdag.opallight.light;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.FastColor;
import net.minecraft.util.FastColor.ARGB32;
import net.neoforged.neoforge.common.util.NeoForgeExtraCodecs;

import java.util.List;

public record OpalColor(float r, float g, float b) {
    private static final Codec<Float> ELEMENT_CODEC = Codec.floatRange(0, 1);
    public static final Codec<OpalColor> ARRAY_CODEC = Codec.list(ELEMENT_CODEC, 3, 3).xmap(OpalColor::of, OpalColor::toList);
    public static final Codec<OpalColor> OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ELEMENT_CODEC.fieldOf("r").forGetter(OpalColor::r),
            ELEMENT_CODEC.fieldOf("g").forGetter(OpalColor::g),
            ELEMENT_CODEC.fieldOf("b").forGetter(OpalColor::b)
    ).apply(instance, OpalColor::new));
    public static final Codec<OpalColor> NUMBER_CODEC = Codec.INT.xmap(OpalColor::of, OpalColor::toInt);
    public static final Codec<OpalColor> STRING_CODEC = Codec.STRING.comapFlatMap(hex -> {
        try {
            return DataResult.success(of(hex));
        } catch (NumberFormatException error) {
            return DataResult.error(() -> "Invalid hexadecimal light color: " + hex);
        }
    }, OpalColor::toString);
    public static final Codec<OpalColor> CODEC = NeoForgeExtraCodecs.withAlternative(
            NeoForgeExtraCodecs.withAlternative(ARRAY_CODEC, OBJECT_CODEC),
            NeoForgeExtraCodecs.withAlternative(NUMBER_CODEC, STRING_CODEC)
    );
    public static final OpalColor EMPTY = new OpalColor(-1, -1, -1);

    public static OpalColor of(float r, float g, float b) {
        return new OpalColor(r, g, b);
    }

    public static OpalColor of(List<Float> rgb) {
        return new OpalColor(rgb.getFirst(), rgb.get(1), rgb.get(2));
    }

    public List<Float> toList() {
        return List.of(r, g, b);
    }

    public static OpalColor of(String hex) {
        return of(Integer.parseInt(hex, 16));
    }

    @Override
    public String toString() {
        String hex = Integer.toString(toInt(), 16);
        int diff = 6 - hex.length();
        if (diff > 0) {
            return "0".repeat(diff) + hex;
        } else if (diff < 0) {
            return hex.substring(-diff);
        }
        return hex;
    }

    public static OpalColor of(int rgb) {
        return new OpalColor(ARGB32.red(rgb) / 255F, ARGB32.green(rgb) / 255F, ARGB32.blue(rgb) / 255F);
    }

    public int toInt() {
        return FastColor.as8BitChannel(r) << 16 | FastColor.as8BitChannel(g) << 8 | FastColor.as8BitChannel(b);
    }
}
