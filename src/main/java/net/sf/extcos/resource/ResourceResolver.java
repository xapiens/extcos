package net.sf.extcos.resource;

import static net.sf.extcos.util.StringUtils.append;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import net.sf.extcos.internal.URLResource;
import net.sf.extcos.internal.vfs.VfsResourceResolver;
import net.sf.extcos.selector.Package;
import net.sf.extcos.spi.QueryContext;
import net.sf.extcos.spi.ResourceType;
import net.sf.extcos.util.ReflectionUtils;
import net.sf.extcos.util.ResourceUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourceResolver {
	private static Logger logger = LoggerFactory.getLogger(ResourceResolver.class);

	private final ClassLoader classLoader = QueryContext.getInstance().getClassLoader();

	private Method equinoxResolveMethod;
	private boolean attemptedToLoadEquinoxResolveMethod = false;

	private Method getEquinoxResolveMethod() {
		if (!attemptedToLoadEquinoxResolveMethod
				&& equinoxResolveMethod == null) {
			// Detect Equinox OSGi (e.g. on WebSphere 6.1)
			// Many thanks to the Spring Framework :)
			try {
				Class<?> fileLocatorClass = getClass().getClassLoader()
						.loadClass("org.eclipse.core.runtime.FileLocator");
				equinoxResolveMethod = fileLocatorClass.getMethod("resolve",
						new Class[] { URL.class });
				logger
				.debug("Found Equinox FileLocator for OSGi bundle URL resolution");
			} catch (Throwable ex) {
				equinoxResolveMethod = null;
			} finally {
				attemptedToLoadEquinoxResolveMethod = true;
			}
		}

		return equinoxResolveMethod;
	}

	public Set<Resource> getResources(final Set<ResourceType> resourceTypes,
			final Package basePackage) {
		try {
			Set<URL> rootDirectories = getRootDirectories(basePackage);
			Set<Resource> resources = new HashSet<Resource>();
			String subPathPattern = basePackage.getSubPathPattern();
			
			for (URL rootDirectory : rootDirectories) {
				rootDirectory = resolveRootDirectory(rootDirectory);

				if (ResourceUtils.isJarURL(rootDirectory)) {
					resources.addAll(findJarResources(resourceTypes, rootDirectory,
							subPathPattern));
				} else if (ResourceUtils.isVirtualFileSystemURL(rootDirectory)) {
					resources.addAll(findVFSResources(resourceTypes, rootDirectory,
							subPathPattern));
				} else {
					resources.addAll(findFileResources(resourceTypes, rootDirectory,
							subPathPattern));
				}
			}

			return resources;
		} catch (Exception e) {
			return Collections.emptySet();
		}
	}

	private Set<URL> getRootDirectories(final Package basePackage) {
		try {
			Enumeration<URL> urlEnum = getClassLoader().getResources(
					basePackage.getBasePath());

			Set<URL> rootDirectories = new LinkedHashSet<URL>();

			while (urlEnum.hasMoreElements()) {
				rootDirectories.add(urlEnum.nextElement());
			}

			if (logger.isDebugEnabled()) {
				logger.debug(append(
						"Found root directories for base package [",
						basePackage.getName(), "]: ", rootDirectories));
			}

			return rootDirectories;
		} catch (IOException e) {
			if (logger.isWarnEnabled()) {
				logger.warn(append("IOException occurred while trying",
						" to get the root directories for base package [",
						basePackage.getName(), "]"), e);
			}
			return Collections.emptySet();
		}
	}

	private ClassLoader getClassLoader() {
		return classLoader;
	}

	private URL resolveRootDirectory(final URL original) {
		if (getEquinoxResolveMethod() != null) {
			if (original.getProtocol().startsWith("bundle")) {
				return (URL) ReflectionUtils.invokeMethod(equinoxResolveMethod,
						null, new Object[] { original });
			}
		}
		return original;
	}

	private Set<Resource> findJarResources(final Set<ResourceType> resourceTypes,
			final URL rootDirectory, String subPathPattern) throws IOException {
		URLConnection con = rootDirectory.openConnection();
		JarFile jarFile = null;
		String jarFileUrl = null;
		String rootEntryPath = null;
		boolean newJarFile = false;

		if (con instanceof JarURLConnection) {
			// Should usually be the case for traditional JAR files.
			JarURLConnection jarCon = (JarURLConnection) con;
			jarCon.setUseCaches(false);
			jarFile = jarCon.getJarFile();
			jarFileUrl = jarCon.getJarFileURL().toExternalForm();
			JarEntry jarEntry = jarCon.getJarEntry();
			rootEntryPath = jarEntry != null ? jarEntry.getName() : "";
		}
		else {
			// No JarURLConnection -> need to resort to URL file parsing.
			// We'll assume URLs of the format "jar:path!/entry", with the protocol
			// being arbitrary as long as following the entry format.
			// We'll also handle paths with and without leading "file:" prefix.
			String urlFile = rootDirectory.getFile();
			int separatorIndex = urlFile.indexOf(ResourceUtils.JAR_URL_SEPARATOR);
			if (separatorIndex != -1) {
				jarFileUrl = urlFile.substring(0, separatorIndex);
				rootEntryPath = urlFile.substring(separatorIndex + ResourceUtils.JAR_URL_SEPARATOR.length());
				jarFile = getJarFile(jarFileUrl);
			}
			else {
				jarFile = new JarFile(urlFile);
				jarFileUrl = urlFile;
				rootEntryPath = "";
			}
			newJarFile = true;
		}

		try {
			if (logger.isDebugEnabled()) {
				logger.debug("Looking for matching resources in jar file [" + jarFileUrl + "]");
			}
			if (!"".equals(rootEntryPath) && !rootEntryPath.endsWith("/")) {
				// Root entry path must end with slash to allow for proper matching.
				// The Sun JRE does not return a slash here, but BEA JRockit does.
				rootEntryPath = rootEntryPath + "/";
			}

			Set<Resource> resources = new LinkedHashSet<Resource>();

			for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements();) {
				JarEntry entry = entries.nextElement();
				if (isCandidate(entry, rootEntryPath, subPathPattern)) {
					String entryPath = entry.getName();
					for (ResourceType resourceType : resourceTypes) {
						if (matches(entryPath, resourceType)) {
							String relativePath = entryPath.substring(rootEntryPath.length());
							URL resourceUrl = new URL(rootDirectory, relativePath);
							resources.add(new URLResource(resourceType, resourceUrl));
							break;
						}
					}
				}
			}

			return resources;
		}
		finally {
			// Close jar file, but only if freshly obtained -
			// not from JarURLConnection, which might cache the file reference.
			if (newJarFile) {
				jarFile.close();
			}
		}
	}

	/**
	 * Resolve the given jar file URL into a JarFile object.
	 */
	private JarFile getJarFile(final String jarFileUrl) throws IOException {
		if (jarFileUrl.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
			try {
				return new JarFile(ResourceUtils.toURI(jarFileUrl).getSchemeSpecificPart());
			}
			catch (URISyntaxException ex) {
				// Fallback for URLs that are not valid URIs (should hardly ever happen).
				return new JarFile(jarFileUrl.substring(ResourceUtils.FILE_URL_PREFIX.length()));
			}
		}

		return new JarFile(jarFileUrl);
	}

	private boolean isCandidate(final JarEntry entry, final String rootEntryPath,
			String subPathPattern) {
		return
				!entry.isDirectory() &&
				entry.getName().matches("^" + rootEntryPath + subPathPattern + ".*$");
	}

	private boolean matches(final String path, final ResourceType resourceType) {
		return path.endsWith(resourceType.getFileSuffix());
	}

	private Set<Resource> findVFSResources(final Set<ResourceType> resourceTypes,
			final URL rootDirectory, String subPathPattern) throws IOException {
		return new VfsResourceResolver().findResources(resourceTypes,
				rootDirectory, subPathPattern);
	}

	private Set<Resource> findFileResources(final Set<ResourceType> resourceTypes,
			final URL rootDirectory, String subPathPattern) {
		try {
			File file = ResourceUtils.getFile(rootDirectory).getAbsoluteFile();

			if (file.isDirectory()) {
				String pathPattern = file.getAbsolutePath().replaceAll("\\\\", "/") + "/" +	subPathPattern;
				
				return doFindFileResources(resourceTypes, file, pathPattern,
						 new HashMap<ResourceType, Pattern>(), new LinkedHashSet<Resource>());
			}

			throw new IOException();
		} catch (IOException e) {
			if (logger.isDebugEnabled()) {
				logger
				.debug(
						append(
								"Cannot search for matching files underneath ",
								rootDirectory,
								" because it does not correspond to a directory in the file system"),
								e);
			}
			return Collections.emptySet();
		}
	}

	private Set<Resource> doFindFileResources(final Set<ResourceType> resourceTypes,
			final File root, final String pathPattern, final HashMap<ResourceType, Pattern> patternCache,
			final Set<Resource> resources) {
		File[] files = root.listFiles();

		for (File file : files) {
			if (file.isDirectory()) {
				doFindFileResources(resourceTypes, file, pathPattern, patternCache, resources);
			} else if (file.isFile()) {
				for (ResourceType resourceType : resourceTypes) {
					Pattern filePattern = patternCache.get(resourceType);
					
					if (filePattern == null) {
						filePattern = Pattern.compile("^" + pathPattern + ".*" +
										resourceType.getFileSuffix() + "$");
						patternCache.put(resourceType, filePattern);
					}
					
					Resource resource = createResource(file, filePattern, resourceType);

					if (resource != null) {
						resources.add(resource);
					}

					break;
				}
			}
		}

		return resources;
	}

	private Resource createResource(final File file, Pattern filePattern,
			final ResourceType resourceType) {
		if (matches(file, filePattern)) {
			URL resourceUrl = toURL(file);

			if (resourceUrl != null) {
				return new URLResource(resourceType, resourceUrl);
			}
		}

		return null;
	}

	private boolean matches(final File file, final Pattern filePattern) {
		String filePath = file.getAbsolutePath().replaceAll("\\\\", "/");
		return filePattern.matcher(filePath).matches();
	}

	private URL toURL(final File file) {
		try {
			return ResourceUtils.toURL(file);
		} catch (MalformedURLException e) {
			// should actually never happen
			if (logger.isDebugEnabled()) {
				logger.debug(append("URL creation failed for: ",
						file.getAbsolutePath()), e);
			}
			return null;
		}
	}
}
