/*
 * Copyright (C) 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.everit.i18n.propsxlsconverter.internal;

import static java.util.Optional.ofNullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang.StringEscapeUtils;
import org.everit.i18n.propsxlsconverter.I18nConverter;
import org.everit.i18n.propsxlsconverter.internal.dto.PropKeyRowNumberDTO;
import org.everit.i18n.propsxlsconverter.internal.dto.WorkbookRowDTO;
import org.everit.i18n.propsxlsconverter.internal.workbook.WorkbookReader;
import org.everit.i18n.propsxlsconverter.internal.workbook.WorkbookWriter;

/**
 * The {@link I18nConverter} implementation.
 */
public class I18nConverterImpl implements I18nConverter {

	private static final Pattern PATTERN = Pattern.compile("^([A-Za-z0-9\\._-]+)(?: ?= ?)(.*)$");
	private static final int SEPARATOR_SIZE = 5;

	private static final String UNDERLINE = "_";

	/**
	 * Map key is fileAccces.
	 */
	private Map<String, List<PropKeyRowNumberDTO>> fileAccessPropertyKeyRowNumber = new HashMap<String, List<PropKeyRowNumberDTO>>();

	private void addPropKeyRowNumberToWorkbookKeyMap(final String fileAccess, final String propKey,
			final int rowNumber) {
		List<PropKeyRowNumberDTO> list = fileAccessPropertyKeyRowNumber.get(fileAccess);
		PropKeyRowNumberDTO propKeyRowNumber = new PropKeyRowNumberDTO().propKey(propKey).rowNumber(rowNumber);
		if (list == null) {
			list = new ArrayList<PropKeyRowNumberDTO>();
			list.add(propKeyRowNumber);
			fileAccessPropertyKeyRowNumber.put(fileAccess, list);
		} else {
			list.add(propKeyRowNumber);
		}
	}

	private String calculateDefaultLangFileName(final String fileName, final String searchLang, final int lastIndexOf) {
		String fileNameFirstPart = fileName.substring(0, lastIndexOf);
		String fileNameSecondPart = fileName.substring(lastIndexOf + searchLang.length());
		return fileNameFirstPart + fileNameSecondPart;
	}

	/**
	 * Calculate file access between the working directory and language file.
	 *
	 * @param languageFile
	 *            the language file.
	 * @return the calculated file access path.
	 */
	private String calculateFileAccess(final File languageFile, final String[] languages,
			final String workingDirectory) {
		Path workingDirectoryPath = Paths.get(workingDirectory);
		String languageFileAbsolutePath = languageFile.getAbsolutePath();
		Path languageFilePath = Paths.get(languageFileAbsolutePath);

		String fileName = languageFile.getName();
		for (String lang : languages) {
			String searchLang = UNDERLINE + lang;
			int lastIndexOf = fileName.lastIndexOf(searchLang);
			if (lastIndexOf > -1) {
				String defaultLangFileName = calculateDefaultLangFileName(fileName, searchLang, lastIndexOf);
				String defaultLangFileAbsolutePath = languageFileAbsolutePath.replace(fileName, defaultLangFileName);
				languageFilePath = Paths.get(defaultLangFileAbsolutePath);
			}
		}

		Path relativize = workingDirectoryPath.relativize(languageFilePath);
		return relativize.toString();
	}

	private String calculateLangFileName(final String fileAccess, final String lang,
			final int lastIndexOfFolderSeparator) {
		String fileName = lastIndexOfFolderSeparator > -1 ? fileAccess.substring(lastIndexOfFolderSeparator)
				: fileAccess;
		int lastDotIndex = fileName.lastIndexOf(".");
		return fileName.substring(0, lastDotIndex) + UNDERLINE + lang + fileName.substring(lastDotIndex);
	}

	@Override
	public void exportToXls(final String xlsFileName, final String workingDirectory, final String fileRegularExpression,
			final String[] languages) {

		validateExportParameters(xlsFileName, workingDirectory, fileRegularExpression, languages);

		File workingDirectoryFile = new File(workingDirectory);

		Collection<File> files = getFilesWithSorted(fileRegularExpression, workingDirectoryFile);

		WorkbookWriter workbookWriter = new WorkbookWriter(xlsFileName, languages);

		if (files.isEmpty()) {
			workbookWriter.writeWorkbookToFile();
			return;
		}

		for (File file : files) {
			String lang = getLanguage(file.getName(), languages);
			String fileAccess = calculateFileAccess(file, languages, workingDirectory);

			try (FileInputStream fileInputStream = new FileInputStream(file);
					InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream,
							StandardCharsets.UTF_8);
					BufferedReader br = new BufferedReader(inputStreamReader)) {
				String line = null;
				while ((line = br.readLine()) != null) {
					// ignore empty and comment lines
					if (!"".equals(line) && (line.charAt(0) != '#')) {
						String unescapedLine = StringEscapeUtils.unescapeJava(line);
						Matcher matcher = PATTERN.matcher(unescapedLine);
						String propKey = null;
						String propValue = null;
						if (matcher.matches()) {
							propKey = matcher.group(1);
							propValue = matcher.group(2);
						} else {
							int separatorIndex = getPropertySeparatorIndex(unescapedLine);
							propKey = unescapedLine.substring(0, separatorIndex);
							propValue = unescapedLine.substring(separatorIndex + 1);
						}
						insertOrUpdateWorkbookRow(workbookWriter, lang, fileAccess, propKey, propValue);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Has problem with IO when try to load/process properties " + "files.", e);
			}
		}

		workbookWriter.writeWorkbookToFile();
	}

	private Integer findRowNumber(final String relativePathToDefaultLanguageFile, final String propKey) {
		List<PropKeyRowNumberDTO> list = fileAccessPropertyKeyRowNumber.get(relativePathToDefaultLanguageFile);
		if ((list == null) || list.isEmpty()) {
			return null;
		}
		for (PropKeyRowNumberDTO pkrn : list) {
			if (pkrn.propKey.equals(propKey)) {
				return pkrn.rowNumber;
			}
		}
		return null;
	}

	private Collection<File> getFilesWithSorted(final String fileRegularExpression, final File workingDirectoryFile) {
		Collection<File> files = FileUtils.listFiles(workingDirectoryFile, new RegexFileFilter(fileRegularExpression),
				DirectoryFileFilter.DIRECTORY);

		if (files instanceof List<?>) {
			// guarantees that the first file is the default language file.
			Collections.sort((List<File>) files, (file1, file2) -> file1.getName().compareTo(file2.getName()));
		}
		return files;
	}

	/**
	 * Gets language from file name.
	 *
	 * @param fileName
	 *            the file name.
	 * @return the language (hu, de) or if default language "" (empty string).
	 */
	private String getLanguage(final String fileName, final String[] languages) {
		for (String lang : languages) {
			if (fileName.contains(UNDERLINE + lang)) {
				return lang;
			}
		}
		return "";
	}

	private int getLastIndexOfFolderSeparator(final String fileAccess) {
		int lastIndexOf = fileAccess.lastIndexOf("/");
		if (lastIndexOf == -1) {
			lastIndexOf = fileAccess.lastIndexOf("\\");
		}
		return lastIndexOf;
	}

	private String getPathWithoutFileName(final String fileAccess, final int lastIndexOfFolderSeparator) {
		if (lastIndexOfFolderSeparator > -1) {
			return fileAccess.substring(0, lastIndexOfFolderSeparator);
		}
		return "";
	}

	private int getPropertySeparatorIndex(final String unescapedLine) {
		int[] separators = new int[SEPARATOR_SIZE];
		int index = 0;
		separators[index++] = unescapedLine.indexOf('=');
		separators[index++] = unescapedLine.indexOf(' ');
		separators[index++] = unescapedLine.indexOf(':');
		separators[index++] = unescapedLine.indexOf('\t');
		separators[index++] = unescapedLine.indexOf('\f');
		Arrays.sort(separators);
		for (int i = 0; i < separators.length; i++) {
			if (separators[i] != -1) {
				return separators[i];
			}
		}
		throw new RuntimeException("Not find separator in the line. Unescaped line: [" + unescapedLine + "].");
	}

	@Override
	public void importFromXls(final String xlsFileName, final String workingDirectory) {

		validateImportParameters(xlsFileName, workingDirectory);
		WorkbookReader workbookReader = new WorkbookReader(xlsFileName);
		System.out.println("lines: " + workbookReader.getLastRowNumber());
		Map<String, Properties> langProperties = new HashMap<String, Properties>();

		String[] languages = workbookReader.getLanguages();
		Set<String> fileNames = workbookReader.getFileNames();
		for (String fileName : fileNames) {
			langProperties.put(fileName, new Properties());
		}
		for (String lang : languages) {
			for (String fileName : fileNames) {
				langProperties.put(getFilenameForLang(fileName, lang), new Properties());
			}
		}
		int lastRowNumber = workbookReader.getLastRowNumber();
		for (int i = 1; i < lastRowNumber; i++) {
			WorkbookRowDTO nextRow = workbookReader.getNextRow();
			String propertiesFile = nextRow.propertiesFile;
			langProperties.get(propertiesFile).setProperty(nextRow.propKey, nextRow.defaultLangValue);
			for (String lang : languages) {
				ofNullable(nextRow.langValues.get(lang)).map(String::trim).filter(s -> !s.isEmpty()).ifPresent(v -> {
					langProperties.get(getFilenameForLang(propertiesFile, lang)).setProperty(nextRow.propKey, v);
				});
			}
		}
		langProperties.entrySet().stream().forEach(e -> {
			if (e.getValue().isEmpty()) {
				return;
			}
			Path filePath = Paths.get(workingDirectory, e.getKey());
			filePath.getParent().toFile().mkdirs();
			File file = filePath.toFile();
			try {
				file.createNewFile();
				try (PrintWriter pw = new PrintWriter(file, "UTF-8")) {
					e.getValue().keySet().stream().sorted().forEach(key-> pw.write(String.format("%s=%s\n", key, e.getValue().getProperty(key.toString()))));
				}
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
	}

	private void insertOrUpdateWorkbookRow(final WorkbookWriter workbookWriter, final String lang,
			final String fileAccess, final String propKey, final String propValue) {
		Integer updatedRowNumber = findRowNumber(fileAccess, propKey);
		if (updatedRowNumber == null) {
			int rowNumber = workbookWriter.insertRow(fileAccess, propKey, lang, propValue);
			addPropKeyRowNumberToWorkbookKeyMap(fileAccess, propKey, rowNumber);
		} else {
			workbookWriter.updateRow(updatedRowNumber, lang, propValue);
		}
	}

	private void makeDirectories(final String workingDirectory, final String pathWithoutFileName) {
		File file = new File(workingDirectory, pathWithoutFileName);
		if (!file.exists() && !file.mkdirs()) {
			throw new RuntimeException("Cannot create directories.");
		}
	}

	/**
	 * Validate parameters.
	 *
	 * @param exportedFileName
	 *            the name of the exported file.
	 * @param workingDirectory
	 *            the working directory (Example: c:\\temp or /tmp).
	 * @param fileRegularExpression
	 *            the regex expression to find files which want to export to XLS
	 *            file. Example: .*\.properties$ to find all properties files.
	 * @param languages
	 *            the languages which want to search.
	 *
	 * @throws NullPointerException
	 *             if one of parameter is null.
	 * @throws IllegalArgumentException
	 *             if exportedFileName or workingDirectory or
	 *             fileRegularExpression is empty. If workingDirectory is not
	 *             directory.
	 * @throws java.util.regex.PatternSyntaxException
	 *             if fileRegularExpression is not valid.
	 */
	private void validateExportParameters(final String exportedFileName, final String workingDirectory,
			final String fileRegularExpression, final String[] languages) {

		Objects.requireNonNull(exportedFileName, "Cannot be null exportedFileName.");
		Objects.requireNonNull(workingDirectory, "Cannot be null workingDirectoryName.");
		Objects.requireNonNull(fileRegularExpression, "Cannot be null fileRegularExpression.");
		Objects.requireNonNull(languages, "Cannot be null languages.");

		if (exportedFileName.trim().isEmpty()) {
			throw new IllegalArgumentException("The exportedFileName is empty. Cannot be empty.");
		}
		if (workingDirectory.trim().isEmpty()) {
			throw new IllegalArgumentException("The workingDirectoryName is empty. Cannot be empty.");
		}
		if (fileRegularExpression.trim().isEmpty()) {
			throw new IllegalArgumentException("The fileRegularExpression is empty. Cannot be empty.");
		}

		File workingDirectoryFile = new File(workingDirectory);
		if (!workingDirectoryFile.isDirectory()) {
			throw new RuntimeException("The working directory is not directory.");
		}

		Pattern.compile(fileRegularExpression);
	}

	/**
	 * Validate parameters.
	 *
	 * @param importedFileName
	 *            the name of the imported file.
	 * @param workingDirectory
	 *            the working directory (Example: c:\\temp or /tmp).
	 *
	 * @throws NullPointerException
	 *             if one of parameter is null.
	 * @throws IllegalArgumentException
	 *             if importedFileName or workingDirectory is empty. If
	 *             workingDirectory is not directory.
	 */
	private void validateImportParameters(final String importedFileName, final String workingDirectory) {

		Objects.requireNonNull(importedFileName, "Cannot be null importedFileName.");
		Objects.requireNonNull(workingDirectory, "Cannot be null workingDirectoryName.");

		if (importedFileName.trim().isEmpty()) {
			throw new IllegalArgumentException("The importedFileName is empty. Cannot be empty.");
		}
		if (workingDirectory.trim().isEmpty()) {
			throw new IllegalArgumentException("The workingDirectoryName is empty. Cannot be empty.");
		}

		File workingDirectoryFile = new File(workingDirectory);
		if (!workingDirectoryFile.isDirectory()) {
			throw new RuntimeException("The working directory is not directory.");
		}
	}
	
	private String getFilenameForLang(String basename, String lang) {
		System.out.println(basename);
		int extensionSeparatorIndex = basename.lastIndexOf("."); 
		return basename.substring(0,extensionSeparatorIndex) + "_" + lang + basename.substring(extensionSeparatorIndex);
	}

}
