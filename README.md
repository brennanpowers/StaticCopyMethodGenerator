# StaticCopyMethodGenerator

IntelliJ plugin for generating a static copy method for a Java class

## Description

Adds an option to generate a static copy method for a Java class in IntelliJ's Generate dialog.  Attempts to make deep 
copies when available by using other static copy methods found within the project's class files.  
Attempts to be null-safe and tries to use fluent setters where possible. Adds warnings in comments when a line for a 
field could not be generated, when only a shallow copy could be generated, or when a possible infinite loop is detected
in a bi-directional relationship.

Refer to JetBrain's [Generate Code](https://www.jetbrains.com/help/idea/generating-code.html) documentation for 
information on how to show IntelliJ's Generate dialog.

## Installing from JetBrains Marketplace

Placeholder text for when this application is added to the JetBrains Marketplace.

## Development

Pull Requests and Issue submittals are welcome

### Dependencies

* IntelliJ CE or Ultimate
* OpenJDK 17

### Running/Debugging the Plugin

Execute the `runIde` Gradle task and create a new project for running/debugging or use the `runIde` run configuration.

### Installing the Plugin from Disk

Execute the `jar` Gradle task or run the `jar` run configuration 
and see [Install plugin from disk](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk)

## Author

Brennan Powers
[GitHub](https://github.com/brennanpowers)

## Version History

* 1.0.0-alpha: First release of plugin

## License

This project is licensed under the MIT License - see the LICENSE file for details