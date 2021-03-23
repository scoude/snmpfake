/*
 * 
 * Ce programme permet de simuler un "petit" serveur SNMP répondant à un OID 
 * précis et renvoyant une valeur aléatoire basée entre une borne inférieure
 * et une borne supérieure. Cette valeur est vue dans notre cas comme une 
 * température.
 * Un fichier (le fichier "magique") peut modifier le comportement de la génération 
 * aléatoire en permettant de dépasser la borne haute s'il contient une valeur
 * différente de 0
 * 
 * L'ensemble des paramètres de l'application est présent dans le fichier 
 * config.properties
 * 
 * Ce programme est basé sur la source créée par Petri TILLI
 * et accessible depuis son dépot GIT : https://github.com/Piidro/simple-snmp-nms
 *  
 * Dépendances :
 *      log4j-1.2.17.jar
 *      snmp4j-2.1.0.jar
 *      snmp4j-agent-2.0.6.jar
 * 
 * @author Serge COUDÉ 
 * @version 1.0
 * @date 2021-03
 * @url : http://www.capitchilog.fr
 * @licence CECILL v2.1 (https://cecill.info/licences/Licence_CeCILL_V2.1-fr.txt)
 * 
 * Exemple de requête avec un client snmp sous Linux
 * $ snmpwalk -v 2c -c public 127.0.0.1:1610 1.3.6.1.4.1.9.9.91.1.1.1.1.4
 * renvoi iso.3.6.1.4.1.9.9.91.1.1.1.1.4 = STRING: "90.2"
 * 
 * Exemple de commande pour vérifier si l'application tourne bien 
 * $ netstat -n --udp --listen | grep 1610
 * renvoi udp6       0      0 127.0.0.1:1610          :::*   
 *
 */
package snmpfake;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Properties;
import java.util.logging.Level;

import org.snmp4j.TransportMapping;
import org.snmp4j.agent.BaseAgent;
import org.snmp4j.agent.CommandProcessor;
import org.snmp4j.agent.DuplicateRegistrationException;
import org.snmp4j.agent.MOGroup;
import org.snmp4j.agent.ManagedObject;
import org.snmp4j.agent.mo.MOAccessImpl;
import org.snmp4j.agent.mo.MOScalar;
import org.snmp4j.agent.mo.MOTableRow;
import org.snmp4j.agent.mo.snmp.RowStatus;
import org.snmp4j.agent.mo.snmp.SnmpCommunityMIB;
import org.snmp4j.agent.mo.snmp.SnmpNotificationMIB;
import org.snmp4j.agent.mo.snmp.SnmpTargetMIB;
import org.snmp4j.agent.mo.snmp.StorageType;
import org.snmp4j.agent.mo.snmp.VacmMIB;
import org.snmp4j.agent.security.MutableVACM;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.USM;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;

/**
 * Classe principale du programme
 * 
 * @author Serge COUDÉ
 */
public class SNMPFake extends BaseAgent {

    /**
     * @var Logger logger Système de log pour l'application
     */
    private static final Logger logger = Logger.getLogger("SNMPFake");
    /**
     * @var String _address Adresse IP sur laquelle va écouter le serveur SNMP
     */
    private String _address;
    /**
     * @var int _port Port d"écoute du serveur SNMP (doit être > 1024 si compte non "root"
     */
    private int _port;
    /**
     * @var String _agentId Nom de l'agent SNMP
     */
    private String _agentId;
    /**
     * @var DecimalFormat _df2 Objet pour formater des valeurs décimales 
     */
    private static DecimalFormat _df2;
    /**
     * @var int _minTemp Borne inférieure pour la génération de la température
     */
    private int _minTemp;
    /**
     * @var int _maxTemp Borne supérieure pour la génération de la température
     */
    private int _maxTemp;
    /**
     * @var int _delayTempChange Délai en secondes entre deux changements de valeurs
     */
    private int _delayTempChange;
    /**
     * @var String _pathMagicFile Chemin absolu vers le fichier "magique"
     */
    private String _pathMagicFile;
    /**
     * @var String _oid OID qui pourra être interrogée par un client SNMP
     */
    private String _oid;
    /**
     * @var MOScalar scalar1 Objet encspsulant l'OID fournissant la température 
     */
    private MOScalar<OctetString> scalar1; 

    /**
     * Constructor
     *
     * @throws IOException
     */
    public SNMPFake() throws IOException {
        super(new File("simplest.boot"), null, new CommandProcessor(new OctetString("simplest")));
        this.loadConfig();
        init();
        addShutdownHook();
        getServer().addContext(new OctetString("public"));
        finishInit();
        run();
        sendColdStartNotification();

        //Unregistering the SNMPv2MIB, because we are overriding it:
        this.unregisterManagedObject(this.getSnmpv2MIB());

        DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols();
        otherSymbols.setDecimalSeparator('.');
        this._df2 = new DecimalFormat("#.#", otherSymbols);
        //Setting the System Description OID with our own value:
        scalar1 = new MOScalar<OctetString>(
                new OID("." + this._oid),
                MOAccessImpl.ACCESS_READ_ONLY,
                new OctetString(this.changeTemperature(false)));
        //Registering the ManagedObject:
        this.registerManagedObject(scalar1);

        // On a besoin d'avoir une référence à cette classe dans le thread suivant
        SNMPFake me = this;
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                // Boucle infinie pour répondre toujours aux requêtes
                while (true) {
                    try {
                        // On patiente le délai avant de changer la valeur
                        Thread.sleep(me.getTempChangeDelay() * 1000);
                        me.changeTemperature(true);
                    } catch (InterruptedException e) {
                    }
                }
            }
        });

        logger.info("Agent started: " + getServer().getRegistry().toString());
    }

    /**
     * Charge les informations contenues dans le fichier config.properties
     * en y définissant des valeurs par défaut en l'absence de certaines
     */
    private void loadConfig() {
        Properties p = new Properties();
        try {
            p.load(SNMPFake.class.getResourceAsStream("config.properties"));
            this._address = p.getProperty("iplistening", "127.0.0.1");
            this._port = Integer.parseInt(p.getProperty("portlistening", "161"));
            this._agentId = p.getProperty("agentid", "SNMPFake");
            this._delayTempChange = Integer.parseInt(p.getProperty("tempchangedelay", "60")); // 1 minute par défaut
            this._maxTemp = Integer.parseInt(p.getProperty("maxtemp", "10"));
            this._minTemp = Integer.parseInt(p.getProperty("mintemp", "20"));
            this._pathMagicFile = p.getProperty("magicfile", "/tmp/magicfile.txt");
            this._oid = p.getProperty("oid", "1.1.1.1.1");
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(SNMPFake.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Définie une nouvelle température en fonction des bornes inférieure et
     * supérieure
     * 
     * @param boolean update Si vrai, l'OID est modifiée, si faux le renvoie que la nouvelle valeur
     * @return String La nouvelle valeur de la température sous forme de chaîne de caractères
     */
    public String changeTemperature(boolean update) {
        int minTemp = this._minTemp;
        int maxTemp = this._maxTemp;
        if (this.depassement()) {
            // Si on peut dépasser, alors la borne supérieure est "relevée"
            maxTemp += (maxTemp - minTemp);
        }
        double temperature = minTemp + Math.random() * (maxTemp - minTemp);
        String oidValue = this._df2.format(temperature);
        if (update) {
            // Si vrai alors on met à jour la valeur qui sera servie par l'OID
            scalar1.setValue(new OctetString(oidValue));
        }
        return oidValue;
    }
    /**
     * Vérifie si le dépassament est autorisé ou non en lisant le contenu
     * du fichier "magique"
     * 
     * @return boolean
     */
    private boolean depassement() {
        java.util.Scanner lecteur;
        java.io.File fichier = new java.io.File(this._pathMagicFile);
        try {
            lecteur = new java.util.Scanner(fichier);
            if (lecteur.hasNextInt()) {
                if (lecteur.nextInt() != 0) {
                    return true;
                }
            }
        } catch (FileNotFoundException ex) {
        }
        return false;
    }

    /**
     * Unregisters a managed object
     *
     * @param moGroup
     */
    public void unregisterManagedObject(MOGroup moGroup) {
        moGroup.unregisterMOs(server, getContext(moGroup));
    }

    /**
     * Registers a managed object
     *
     * @param mo
     */
    public void registerManagedObject(ManagedObject mo) {
        try {
            server.register(mo, null);
        } catch (DuplicateRegistrationException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    protected void registerManagedObjects() {
    }

    @Override
    protected void unregisterManagedObjects() {
    }

    @Override
    protected void addUsmUser(USM usm) {
    }

    @Override
    protected void addNotificationTargets(SnmpTargetMIB targetMIB, SnmpNotificationMIB notificationMIB) {
    }

    /**
     * Init Transport mappings, SNMP4J stuff
     */
    @Override
    protected void initTransportMappings() throws IOException {
        transportMappings = new TransportMapping[1];
        UdpAddress udpAddress = new UdpAddress(this._address + "/" + Integer.toString(this._port));
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(udpAddress);
        transportMappings[0] = transport;
    }

    /**
     * Add views, SNMP4J stuff
     */
    @Override
    protected void addViews(VacmMIB vacm) {
        vacm.addGroup(SecurityModel.SECURITY_MODEL_SNMPv2c, new OctetString(
                "cpublic"), new OctetString("v1v2group"),
                StorageType.nonVolatile);

        vacm.addAccess(new OctetString("v1v2group"), new OctetString("public"),
                SecurityModel.SECURITY_MODEL_ANY, SecurityLevel.NOAUTH_NOPRIV,
                MutableVACM.VACM_MATCH_EXACT, new OctetString("fullReadView"),
                new OctetString("fullWriteView"), new OctetString(
                        "fullNotifyView"), StorageType.nonVolatile);

        vacm.addViewTreeFamily(new OctetString("fullReadView"), new OID("1.3"),
                new OctetString(), VacmMIB.vacmViewIncluded,
                StorageType.nonVolatile);
    }

    /**
     * Add communities, SNMP4J stuff
     */
    @Override
    protected void addCommunities(SnmpCommunityMIB communityMIB) {
        Variable[] com2sec = new Variable[]{
            new OctetString("public"), // community name
            new OctetString("cpublic"), // security name
            getAgent().getContextEngineID(), // local engine ID
            new OctetString("public"), // default context name
            new OctetString(), // transport tag
            new Integer32(StorageType.nonVolatile), // storage type
            new Integer32(RowStatus.active) // row status
        };
        MOTableRow row = communityMIB.getSnmpCommunityEntry().createRow(
                new OctetString("public2public").toSubIndex(true), com2sec);
        communityMIB.getSnmpCommunityEntry().addRow(row);
    }

    public String getAddress() {
        return this._address;
    }

    public String getAgentId() {
        return this._agentId;
    }

    public int getPort() {
        return this._port;
    }

    public int getMinTemp() {
        return this._minTemp;
    }

    public int getMaxTemp() {
        return this._maxTemp;
    }

    public String getMagicFile() {
        return this._pathMagicFile;
    }

    public int getTempChangeDelay() {
        return this._delayTempChange;
    }

    @Override
    public void saveConfig() {
        // Important pour ne pas avoir des erreurs de sauvegardes sur un fichier bidon (simplest.boot)
    }

    /**
     * Point d'entrée de l'application
     * 
     * @param String[] args Arguments éventuellement passés lors de l'appel du programme. Non utilisé
     * @throws IOException 
     */
    public static void main(String[] args) throws IOException {

        BasicConfigurator.configure();
        logger.info("Starting the agent...");
        try {
            new SNMPFake();
        } catch (IOException ioe) {
            logger.error(ioe);
        }
    }

}
