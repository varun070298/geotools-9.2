package org.geotools.data.ingres;

import org.geotools.jdbc.JDBCDataStoreAPITestSetup;
import org.geotools.jdbc.JDBCVirtualTableTest;

/**
 * 
 *
 * @source $URL$
 */
public class IngresVirtualTableTest extends JDBCVirtualTableTest {

    @Override
    protected JDBCDataStoreAPITestSetup createTestSetup() {
        return new IngresDataStoreAPITestSetup(new IngresTestSetup());
    }

}
