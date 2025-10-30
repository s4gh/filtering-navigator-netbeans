# Overview
Plugin for NetBans IDE that provides an alternative implementation of the built-in "Navigator" view. The plugin works exclusively with Java source files. It displays a pop-up window showing the structure of the file - a tree view of members, methods, and other classes. And allows for searching and quick navigation to a selected member.

Main difference from built-in navigator is that when you search for something in this plugin - only maching members are displayed in tree view. Built-in navigator always shows all members even when you search. During search default navigator allows you to jump between matching memebrs using keyboard but you always see all members - not only those who match sarch criteria. Which is inconvnient imho. That is why this plugin provides alternative.

# Quick Start
* from menu select ```Tools->Filtering Code Navigator```
* from kyboard ```Ctrl+Alt+F```
* toggle ```Show Inherited Members``` checkbox from keyboard - ```Ctrl+Alt+F```

# Screenshots
![NetBeans Java Navigator](images/netbeans-navigator.png)
