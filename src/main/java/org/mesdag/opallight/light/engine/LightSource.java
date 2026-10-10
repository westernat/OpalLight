package org.mesdag.opallight.light.engine;

import org.mesdag.opallight.light.data.LightProfile;

record LightSource(long pos, LightProfile profile, int emission) {
}
