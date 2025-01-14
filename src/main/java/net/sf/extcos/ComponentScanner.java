package net.sf.extcos;

import java.util.Set;

import net.sf.extcos.internal.ComponentSelectionProcessor;
import net.sf.extcos.spi.QueryContext;

/**
 * The main entry point to the Extensible Component Scanner.
 * <p>
 * The methods in this class allow scanning for components by
 * passing in a {@link ComponentQuery}.
 * 
 * @author Matthias Rothe
 */
public class ComponentScanner {

	/**
	 * This method lets you specify a component query defining the criteria
	 * components you are interested in must match.
	 * <p>
	 * It looks up the default class loader and uses it to fulfill the
	 * request.
	 * <p>
	 * It returns a set of classes matching the defined criteria of the
	 * query.
	 * <p>
	 * The default class loader is retrieved via
	 * <code>Thread.currentThread().getContextClassLoader();</code>
	 * 
	 * @param componentQuery The component query defining the criteria
	 * 						 components must match to be returned
	 * @return The component classes matching the criteria given with the
	 * 			componentQuery
	 */
	public Set<Class<?>> getClasses(final ComponentQuery componentQuery) {
		return getClasses(componentQuery, getDefaultClassLoader());
	}

	/**
	 * Along with the component query defining the criteria components you
	 * are interested in must match this method lets you specify a custom
	 * class loader to be used to fulfill the request.
	 * <p>
	 * This allows you for example to use the Extensible Component Scanner
	 * within a web container like Tomcat and use its WebappClassLoader.
	 * <p>
	 * This method returns a set of classes matching the defined criteria of
	 * the query.
	 * 
	 * @param componentQuery The component query defining the criteria
	 * 						 components must match to be returned
	 * @param classLoader The custom class loader to be used to fulfill the
	 * 					  request
	 * @return The component classes matching the criteria given with the
	 * 			componentQuery
	 */
	public Set<Class<?>> getClasses(final ComponentQuery componentQuery, final ClassLoader classLoader) {
		QueryContext.getInstance().setClassLoader(classLoader);
		ComponentSelectionProcessor processor = new ComponentSelectionProcessor(componentQuery);
		return processor.process();
	}

	private ClassLoader getDefaultClassLoader() {
		return Thread.currentThread().getContextClassLoader();
	}
}