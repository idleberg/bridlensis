package bridlensis.env;

import static org.junit.Assert.*;

import org.junit.Test;

public class BuiltinElementsTest {

	@Test
	public void testLoadBuiltinFunctions() {
		Environment env = new Environment(
				BuiltinElements.loadBuiltinVariables(),
				BuiltinElements.loadBuiltinFunctions(null));
		try {
			env.registerUserFunction("DetailPrint");
			fail();
		} catch (EnvironmentException e) {
			System.err.println(e.getMessage());
		}
	}

	@Test
	public void testLoadBuiltinVariables() throws EnvironmentException {
		Environment env = new Environment(
				BuiltinElements.loadBuiltinVariables(),
				BuiltinElements.loadBuiltinFunctions(null));
		assertTrue(env.containsVariable("r2", null));
		assertTrue(env.containsVariable("r3", null));
		assertTrue(env.containsVariable("r9", null));
	}

}
