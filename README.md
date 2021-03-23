# snmpfake
Application simulating a mini SNMP server delivering an OID containing a fluctuating decimal value

# Description
Ce programme permet de simuler un "petit" serveur SNMP répondant à un OID 
précis et renvoyant une valeur aléatoire basée entre une borne inférieure
et une borne supérieure. Cette valeur est vue dans notre cas comme une 
température.

Un fichier (le fichier "magique") peut modifier le comportement de la génération 
aléatoire en permettant de dépasser la borne haute s'il contient une valeur
différente de 0

L'ensemble des paramètres de l'application est présent dans le fichier 
config.properties

Ce programme est basé sur la source créée par Petri TILLI
et accessible depuis son dépot GIT : https://github.com/Piidro/simple-snmp-nms
  
Dépendances :
      log4j-1.2.17.jar
      snmp4j-2.1.0.jar
      snmp4j-agent-2.0.6.jar
 
@author Serge COUDÉ 
@version 1.0
@date 2021-03
@url : http://www.capitchilog.fr
@licence CECILL v2.1 (https://cecill.info/licences/Licence_CeCILL_V2.1-fr.txt)
 
Exemple de requête avec un client snmp sous Linux
$ snmpwalk -v 2c -c public 127.0.0.1:1610 1.3.6.1.4.1.9.9.91.1.1.1.1.4
renvoi iso.3.6.1.4.1.9.9.91.1.1.1.1.4 = STRING: "90.2"

Exemple de commande pour vérifier si l'application tourne bien 
$ netstat -n --udp --listen | grep 1610
renvoi udp6       0      0 127.0.0.1:1610          :::*   

