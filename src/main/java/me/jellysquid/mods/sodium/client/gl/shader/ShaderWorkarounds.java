// Copyright 2020 Grondag
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.

package me.jellysquid.mods.sodium.client.gl.shader;

import org.lwjgl.opengl.GL20C;

/**
 * Contains a workaround for a crash in nglShaderSource on some AMD drivers. Copied from the following Canvas commit:
 * https://github.com/grondag/canvas/commit/820bf754092ccaf8d0c169620c2ff575722d7d96
 */
class ShaderWorkarounds {
	/**
	 * Uploads shader source to the driver.
	 *
	 * <p>Upstream (Sodium/Embeddium) used a manual {@code nglShaderSource} call with a null length
	 * pointer (forcing the driver to rely on the null terminator) to work around an access violation
	 * on some AMD drivers. That path relied on LWJGL3-internal APIs ({@code PointerBuffer#address0()},
	 * {@code APIUtil#apiArrayFree}) which the Cleanroom lwjglx compile shim does not expose, so we fall
	 * back to the standard high-level call for now.
	 *
	 * <p>TODO(yumelium): restore the null-length-pointer AMD workaround (e.g. via MemoryStack + MemoryUtil,
	 * without PointerBuffer) once it can be validated against a running client.
	 */
	static void safeShaderSource(int glId, CharSequence source) {
		GL20C.glShaderSource(glId, source);
	}
}
