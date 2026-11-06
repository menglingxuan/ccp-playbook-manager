package ac.domain.casefile;

import ac.domain.product.ProductSchema;

/**
 * Everything the converter needs besides the raw row.
 *
 * @param schema              product schema
 * @param layout              resolved sheet layout
 * @param fileName            original upload file name, case preserved
 * @param sheetName           sheet name
 * @param hashLength          length of the generated record ids
 * @param normalizeFormulaRefs when true, relative row numbers inside formulas are masked so the
 *                             same formula on different rows yields the same business id
 */
public record ConversionContext(
        ProductSchema schema,
        ac.domain.layout.SheetLayout layout,
        String fileName,
        String sheetName,
        int hashLength,
        boolean normalizeFormulaRefs) {
}
